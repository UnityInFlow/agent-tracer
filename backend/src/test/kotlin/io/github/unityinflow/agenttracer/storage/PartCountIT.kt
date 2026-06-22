package io.github.unityinflow.agenttracer.storage

import io.github.unityinflow.agenttracer.storage.migrations.MigrationRunner
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * SC4 (ROADMAP success criterion 4): sustained batched insert load through the real
 * [SpanWriter] → [ClickHouseSpanRepository] keeps the ClickHouse **active part count** for the
 * `spans` table bounded — proving the Channel batcher prevents the `TOO_MANY_PARTS` anti-pattern
 * (Pitfall 1). If writes hit ClickHouse per-row instead of in batches, active parts would explode
 * well past the too-many-parts threshold.
 *
 * Rows carry REAL distinct identity from [SpanFixtures.traceOf] spread across many traces and many
 * `toDate(Timestamp)` partitions — an all-empty identity would collapse into ONE `(TraceId, SpanId)`
 * key and ONE partition, making the part-count assertion trivially (falsely) pass.
 *
 * Runs against the shared JVM-wide [ClickHouseTestSupport] container (image pinned 26.3).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartCountIT {
    private val client = ClickHouseTestSupport.newClient()
    private val repo = ClickHouseSpanRepository(client)
    private val mapper = AgentSpanRowMapper()

    @BeforeAll
    fun applySchema() {
        runBlocking {
            ClickHouseTestSupport.container
            MigrationRunner(client).runMigrations().getOrThrow()
        }
    }

    @Test
    fun sustainedBatchedLoadKeepsActivePartCountBounded() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val writer =
                SpanWriter(
                    repo = repo,
                    mapper = mapper,
                    scope = scope,
                    // Small batchSize + short interval so MANY flushes happen across the run —
                    // each flush is still a BATCH (not per-row), which is exactly what keeps
                    // active parts bounded. Per-row inserts at this volume would blow past the cap.
                    config =
                        SpanWriter.Config(
                            capacity = 50_000,
                            batchSize = 2_000,
                            flushInterval = 500.milliseconds,
                            maxRetries = 4,
                        ),
                )

            // 30 traces × 1000 spans = 30_000 spans, spread across distinct (TraceId, SpanId) keys
            // and across many daily partitions (each trace based at a different day).
            val traces = 30
            val spansPerTrace = 1_000
            val baseDay = Instant.parse("2026-01-01T00:00:00Z")
            repeat(traces) { t ->
                val traceId = "partcount-trace-%010d".format(t)
                // Spread each trace onto its own day so rows land in many toDate(Timestamp) partitions.
                val baseTime = baseDay.plusSeconds(t.toLong() * 86_400L)
                val pairs = SpanFixtures.traceOf(spansPerTrace, traceId = traceId, baseTime = baseTime)
                for ((span, ctx) in pairs) {
                    writer.send(span, ctx)
                }
            }

            // Flush the in-flight buffer and stop the writer (graceful drain — no loss).
            writer.drainAndClose()

            // Wait until all rows are visible (async-visible inserts), bounded poll (no Thread.sleep).
            val expected = (traces * spansPerTrace).toLong()
            waitForTotalSpans(expected)

            // SC4 assertion: ClickHouse keeps active parts for `spans` well under the
            // too-many-parts danger zone (~300). Batching is the reason it stays bounded.
            val activeParts =
                client
                    .queryAll("SELECT count() AS c FROM system.parts WHERE table = 'spans' AND active = 1")
                    .single()
                    .getLong("c")

            // All sent rows are visible (no loss through the batcher) and active parts stay bounded.
            client
                .queryAll("SELECT count() AS c FROM spans")
                .single()
                .getLong("c") shouldBeGreaterThanOrEqual expected
            activeParts shouldBeLessThan ACTIVE_PARTS_THRESHOLD
        }
    }

    private suspend fun waitForTotalSpans(expected: Long) {
        repeat(POLL_ATTEMPTS) {
            val count =
                client
                    .queryAll("SELECT count() AS c FROM spans")
                    .single()
                    .getLong("c")
            if (count >= expected) return
            delay(POLL_DELAY_MS)
        }
    }

    private companion object {
        /** Too-many-parts danger zone is ~300 active parts/partition; stay well under (Pitfall 1). */
        const val ACTIVE_PARTS_THRESHOLD = 300L
        const val POLL_ATTEMPTS = 100
        const val POLL_DELAY_MS = 200L
    }
}
