package io.github.unityinflow.agenttracer.storage

import io.github.unityinflow.agenttracer.storage.migrations.MigrationRunner
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Proves every [ClickHouseSpanRepository] read/write path round-trips against a real ClickHouse
 * (Testcontainers, image pinned 26.3, shared JVM-wide container from [ClickHouseTestSupport]):
 *
 *  1. [typedAgentColumnsRoundTrip] — STOR-01: typed agent values inserted via [SpanRepository.writeBatch]
 *     are queryable as first-class columns (NOT Map keys) through [SpanRepository.getTraceById].
 *  2. [getTraceByIdReturnsExactlyTheTracesSpans] — getTraceById returns exactly the trace's spans
 *     via the MV path, excluding other traces.
 *  3. [payloadsRoundTripIncludingTruncation] / [getPayloadForAbsentSpanReturnsNull] — STOR-03:
 *     payload bodies + truncation metadata round-trip by (TraceId, SpanId); absent → null.
 *  4. [agentRunsRollupIsCorrectWithDedup] — agent_runs rollup math (summed tokens/cost, max
 *     saturation, span count, anyLast agent/model/outcome) is correct for a complete/in-order
 *     uniform-dimension trace, including a duplicate-span insert (D-07 dedup scope).
 *  5. [listRunsHonorsFilters] — listRuns applies agent/model/cost-range/outcome/date filters.
 *
 * Every trace uses a DISTINCT TraceId so the shared container's accumulated state never cross-
 * contaminates assertions. Real (non-empty) identity from [SpanFixtures]; no mocks, no second
 * container.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpanRepositoryIT {
    private val client = ClickHouseTestSupport.newClient()
    private val repo = ClickHouseSpanRepository(client)

    @BeforeAll
    fun applySchema() {
        runBlocking {
            ClickHouseTestSupport.container
            // Converge the schema (0 applied when a prior *IT already created it on the shared container).
            MigrationRunner(client).runMigrations().getOrThrow()
        }
    }

    @Test
    fun typedAgentColumnsRoundTrip() =
        runBlocking {
            val traceId = "stor01-typed-cols-000000000000001"
            val (span, ctx) = SpanFixtures.full(traceId = traceId, spanId = "1111111111111111")
            val row = SpanRow.from(span, ctx, spanName = "chat", serviceName = "agent-tracer-it")

            repo.writeBatch(listOf(row), emptyList()).getOrThrow()
            waitForSpans(traceId, expected = 1)

            val fetched = repo.getTraceById(traceId).getOrThrow()
            fetched shouldHaveSize 1
            val got = fetched.single()
            // Typed first-class agent columns round-trip (NOT stuffed into the attribute Maps).
            got.agentName shouldBe "planner-agent"
            got.model shouldBe "claude-opus-4"
            got.skillName shouldBe "code-review"
            got.inputTokens shouldBe 1_200L
            got.outputTokens shouldBe 350L
            got.costUsd!! shouldBeExactly 0.0425
            got.contextSaturationPct!! shouldBeExactly 62.5f
            got.planStep shouldBe 3L
            got.outcome shouldBe "SUCCESS"
            // The typed values are NOT smuggled into the leftover attribute maps (Pitfall 3).
            val agentDims = setOf("gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "unityinflow.cost.usd")
            (got.spanAttributes.keys intersect agentDims) shouldBe emptySet()
        }

    @Test
    fun getTraceByIdReturnsExactlyTheTracesSpans() =
        runBlocking {
            val traceId = "stor02-trace-by-id-00000000000001"
            val otherTrace = "stor02-other-trace-0000000000002"
            val trace = SpanFixtures.traceOf(4, traceId = traceId)
            val other = SpanFixtures.traceOf(2, traceId = otherTrace)

            val rows = (trace + other).map { (s, c) -> SpanRow.from(s, c, spanName = "chat", serviceName = "it") }
            repo.writeBatch(rows, emptyList()).getOrThrow()
            waitForSpans(traceId, expected = 4)

            val fetched = repo.getTraceById(traceId).getOrThrow()
            fetched shouldHaveSize 4
            fetched.map { it.traceId }.toSet() shouldBe setOf(traceId)
            fetched.map { it.spanId } shouldContainExactlyInAnyOrder trace.map { it.second.spanId }
        }

    @Test
    fun payloadsRoundTripIncludingTruncation() =
        runBlocking {
            val traceId = "stor03-payloads-0000000000000001"
            val (fullSpan, fullCtx) = SpanFixtures.full(traceId = traceId, spanId = "aaaaaaaaaaaaaaaa")
            val (truncSpan, truncCtx) = SpanFixtures.truncatedPayload(traceId = traceId, spanId = "bbbbbbbbbbbbbbbb")

            val payloads =
                listOf(
                    PayloadRow.from(fullSpan, fullCtx),
                    PayloadRow.from(truncSpan, truncCtx),
                )
            repo.writeBatch(emptyList(), payloads).getOrThrow()

            // Non-truncated payload round-trips verbatim.
            val gotFull = repo.getPayload(traceId, "aaaaaaaaaaaaaaaa").getOrThrow().shouldNotBeNull()
            gotFull.inputPayload shouldBe fullSpan.inputPayload
            gotFull.outputPayload shouldBe fullSpan.outputPayload
            gotFull.systemInstructions shouldBe fullSpan.systemInstructions
            gotFull.payloadTruncated shouldBe false
            gotFull.payloadOriginalSizeBytes shouldBe 137L

            // Truncated payload preserves the cap'd bodies + truncation metadata (STOR-03 / D-13).
            val gotTrunc = repo.getPayload(traceId, "bbbbbbbbbbbbbbbb").getOrThrow().shouldNotBeNull()
            gotTrunc.inputPayload.length shouldBe SpanFixtures.PAYLOAD_CAP_BYTES
            gotTrunc.outputPayload.length shouldBe SpanFixtures.PAYLOAD_CAP_BYTES
            gotTrunc.payloadTruncated shouldBe true
            gotTrunc.payloadOriginalSizeBytes shouldBe (SpanFixtures.PAYLOAD_CAP_BYTES * 4).toLong()
        }

    @Test
    fun getPayloadForAbsentSpanReturnsNull() =
        runBlocking {
            repo.getPayload("no-such-trace-000000000000000001", "deadbeefdeadbeef").getOrThrow().shouldBeNull()
        }

    @Test
    fun agentRunsRollupIsCorrectWithDedup() =
        runBlocking {
            val traceId = "rollup-trace-00000000000000000001"
            val spans = SpanFixtures.traceOf(3, traceId = traceId)
            val rows = spans.map { (s, c) -> SpanRow.from(s, c, spanName = "chat", serviceName = "it") }

            // Insert the trace, then RE-INSERT the first span (duplicate) — the rollup must dedup
            // by the MV's GROUP BY TraceId across the in-order, uniform-dimension trace (D-07 scope).
            repo.writeBatch(rows, emptyList()).getOrThrow()
            repo.writeBatch(listOf(rows.first()), emptyList()).getOrThrow()
            waitForRun(traceId)

            val run = repo.listRuns(RunFilter(agentName = "planner-agent")).getOrThrow().single { it.traceId == traceId }

            // full() fixtures: inputTokens=1200, outputTokens=350, costUsd=0.0425, saturation=62.5 each.
            // traceOf keeps these uniform across the 3 spans; agent_runs sums tokens/cost and maxes saturation.
            // (The duplicate first span is folded by GROUP BY TraceId at MV insert time → no double count.)
            run.agentName shouldBe "planner-agent"
            run.model shouldBe "claude-opus-4"
            run.outcome shouldBe "SUCCESS"
            run.spanCount shouldBe 3L
            run.inputTokens shouldBe 3_600L
            run.outputTokens shouldBe 1_050L
            run.costUsd shouldBeExactly 0.1275
            run.maxSaturation shouldBeExactly 62.5f
        }

    @Test
    fun listRunsHonorsFilters() =
        runBlocking {
            val matching = "filter-match-0000000000000000001"
            val other = "filter-other-0000000000000000002"
            val matchRows =
                SpanFixtures
                    .traceOf(2, traceId = matching, agentName = "alpha-agent", model = "model-x")
                    .map { (s, c) -> SpanRow.from(s, c, spanName = "chat", serviceName = "it") }
            val otherRows =
                SpanFixtures
                    .traceOf(2, traceId = other, agentName = "beta-agent", model = "model-y")
                    .map { (s, c) -> SpanRow.from(s, c, spanName = "chat", serviceName = "it") }

            repo.writeBatch(matchRows + otherRows, emptyList()).getOrThrow()
            waitForRun(matching)
            waitForRun(other)

            // Filter by agent + model + cost range + outcome + date range → only the matching run.
            val filter =
                RunFilter(
                    agentName = "alpha-agent",
                    model = "model-x",
                    costMin = 0.0,
                    costMax = 1_000.0,
                    outcome = "SUCCESS",
                    dateFrom = SpanFixtures.BASE_TIME.minusSeconds(60),
                    dateTo = SpanFixtures.BASE_TIME.plusSeconds(60),
                )
            val runs = repo.listRuns(filter).getOrThrow()
            runs.map { it.traceId } shouldContainExactlyInAnyOrder listOf(matching)
        }

    /**
     * Polls until [expected] spans for [traceId] are visible. ClickHouse inserts are async-visible;
     * this avoids a flaky read-before-merge without a `Thread.sleep` readiness loop.
     */
    private suspend fun waitForSpans(
        traceId: String,
        expected: Int,
    ) {
        repeat(POLL_ATTEMPTS) {
            val count =
                client
                    .queryAll(
                        "SELECT count() AS c FROM spans WHERE TraceId = {tid:String}",
                        mapOf("tid" to traceId),
                    ).single()
                    .getLong("c")
            if (count >= expected) return
            kotlinx.coroutines.delay(POLL_DELAY_MS)
        }
    }

    /** Polls until the [traceId] run materialises in agent_runs (MV insert is async-visible). */
    private suspend fun waitForRun(traceId: String) {
        repeat(POLL_ATTEMPTS) {
            val count =
                client
                    .queryAll(
                        "SELECT count() AS c FROM agent_runs FINAL WHERE TraceId = {tid:String}",
                        mapOf("tid" to traceId),
                    ).single()
                    .getLong("c")
            if (count >= 1L) return
            kotlinx.coroutines.delay(POLL_DELAY_MS)
        }
    }

    private companion object {
        const val POLL_ATTEMPTS = 50
        const val POLL_DELAY_MS = 100L
    }
}
