package io.github.unityinflow.agenttracer.storage

import io.github.unityinflow.agenttracer.storage.migrations.MigrationRunner
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Proves the Phase-2 schema against a real ClickHouse (Testcontainers, image pinned 26.3):
 *  1. [migrationsCreateAllObjects] — `runMigrations()` materialises all four objects, both
 *     MVs, and `schema_migrations` in `system.tables` (STOR-01 structurally present).
 *  2. [reRunningMigrationsIsANoOp] — a second `runMigrations()` applies nothing and leaves
 *     exactly one `schema_migrations` row per version (idempotent despite no transactional DDL).
 *  3. [traceByIdPrunesGranules] — inserts a real multi-span trace, reads its time range from the
 *     `trace_id_ts` MV, then `EXPLAIN indexes = 1` on the bounded trace-by-id query shows
 *     granule pruning (g « G), proving STOR-02 avoids a full scan.
 *  4. [traceIdTsReturnsCorrectRange] — the `trace_id_ts` MV returns the correct (MinTime, MaxTime)
 *     for the trace.
 *
 * Runs against the shared JVM-wide container from [ClickHouseTestSupport]; no mocks, no second
 * container. Real (non-empty) span identity from [SpanFixtures.traceOf] so pruning is genuine.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseSchemaIT {
    private val client = ClickHouseTestSupport.newClient()

    private companion object {
        /** ClickHouse DateTime64 literal format the parameterized inserts use. */
        val TS_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC)
    }

    @BeforeAll
    fun applySchema() {
        runBlocking {
            // Touch the shared container so it is started before the first query.
            ClickHouseTestSupport.container
            MigrationRunner(client).runMigrations().getOrThrow() shouldBeGreaterThan 0
        }
    }

    @AfterAll
    fun closeClient() {
        client.close()
    }

    @Test
    fun migrationsCreateAllObjects() =
        runBlocking {
            val tables =
                client
                    .queryAll("SELECT name FROM system.tables WHERE database = currentDatabase()")
                    .map { it.getString("name") }
            tables shouldContainAll
                listOf(
                    "spans",
                    "payloads",
                    "trace_id_ts",
                    "trace_id_ts_mv",
                    "agent_runs",
                    "agent_runs_mv",
                    "schema_migrations",
                )
        }

    @Test
    fun reRunningMigrationsIsANoOp() =
        runBlocking {
            // Schema already applied in @BeforeAll → a second run records nothing new.
            MigrationRunner(client).runMigrations().getOrThrow() shouldBe 0

            // And exactly one tracking row exists per version (no duplicate applies).
            val perVersion =
                client
                    .queryAll(
                        "SELECT Version, count() AS c FROM schema_migrations GROUP BY Version ORDER BY Version",
                    )
            perVersion.size shouldBe 4
            perVersion.forEach { it.getLong("c") shouldBe 1L }
        }

    @Test
    fun traceByIdPrunesGranules() =
        runBlocking {
            val traceId = "trace-prune-0000000000000000000001"
            insertTrace(traceId, spanCount = 8)

            // STOR-02 step 1: read the time range for this trace from the MV.
            val range =
                client
                    .queryAll(
                        "SELECT MinTime AS lo, MaxTime AS hi FROM trace_id_ts FINAL WHERE TraceId = {tid:String}",
                        mapOf("tid" to traceId),
                    ).single()
            val lo = TS_FORMAT.format(range.getInstant("lo"))
            val hi = TS_FORMAT.format(range.getInstant("hi"))

            // STOR-02 step 2: EXPLAIN the bounded trace-by-id query and assert granule pruning.
            val plan =
                client
                    .queryAll(
                        """
                        EXPLAIN indexes = 1
                        SELECT * FROM spans
                        WHERE TraceId = {tid:String}
                          AND Timestamp BETWEEN {lo:DateTime64(9)} AND {hi:DateTime64(9)}
                        """.trimIndent(),
                        mapOf("tid" to traceId, "lo" to lo, "hi" to hi),
                    ).joinToString("\n") { it.getString(0) }

            // Parse the "Granules: g/G" line — pruning means selected g < total G.
            val granules = Regex("""Granules:\s*(\d+)/(\d+)""").find(plan)
            requireNotNull(granules) { "EXPLAIN plan reported no Granules line:\n$plan" }
            val selected = granules.groupValues[1].toLong()
            val total = granules.groupValues[2].toLong()
            total shouldBeGreaterThan 0L
            selected shouldBeLessThan total
        }

    @Test
    fun traceIdTsReturnsCorrectRange() =
        runBlocking {
            val traceId = "trace-range-0000000000000000000002"
            val spanCount = 5
            insertTrace(traceId, spanCount)

            val range =
                client
                    .queryAll(
                        "SELECT MinTime AS lo, MaxTime AS hi FROM trace_id_ts FINAL WHERE TraceId = {tid:String}",
                        mapOf("tid" to traceId),
                    ).single()

            // traceOf spans are 1s apart starting at BASE_TIME; min = base, max = base + (n-1)s.
            val expectedMin = SpanFixtures.BASE_TIME
            val expectedMax = SpanFixtures.BASE_TIME.plusSeconds((spanCount - 1).toLong())
            range.getInstant("lo") shouldBe expectedMin
            range.getInstant("hi") shouldBe expectedMax
        }

    /**
     * Thin direct insert of a real [SpanFixtures.traceOf] trace: distinct SpanIds, a shared
     * TraceId, and timestamps spread 1s apart, so the rows occupy multiple granules and pruning
     * is genuinely exercised (never empty-string identity, which would collapse to one key).
     */
    private suspend fun insertTrace(
        traceId: String,
        spanCount: Int,
    ) {
        SpanFixtures.traceOf(spanCount, traceId = traceId).forEach { (span, ctx) ->
            client
                .execute(
                    """
                    INSERT INTO spans
                      (Timestamp, TraceId, SpanId, ParentSpanId, SpanName, ServiceName, Duration,
                       StatusCode, AgentName, Model, Operation, Outcome)
                    VALUES
                      ({ts:DateTime64(9)}, {tid:String}, {sid:String}, {psid:String}, {name:String},
                       {svc:String}, {dur:UInt64}, {status:String}, {agent:String}, {model:String},
                       {op:String}, {outcome:String})
                    """.trimIndent(),
                    mapOf(
                        "ts" to TS_FORMAT.format(ctx.timestamp),
                        "tid" to ctx.traceId,
                        "sid" to ctx.spanId,
                        "psid" to ctx.parentSpanId,
                        "name" to "chat",
                        "svc" to "agent-tracer-it",
                        "dur" to 1_000L,
                        "status" to "OK",
                        "agent" to (span.agentName ?: ""),
                        "model" to (span.model ?: ""),
                        "op" to (span.operation?.name ?: ""),
                        "outcome" to span.outcome.name,
                    ),
                ).await()
        }
    }
}
