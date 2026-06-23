// Wave 0 stub — fails until Plan 02 creates RunListMapper / RunSummary / RunListResponse.
package io.github.unityinflow.agenttracer.query

import io.github.unityinflow.agenttracer.storage.AgentRunRow
import io.github.unityinflow.agenttracer.storage.SpanRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Wave-0 RED scaffold for the run-list query-param → filter mapper (Plan 02 produces the symbols).
 *
 * Pins SC3a:
 *  - query-param map → `RunFilter`: `costMin`/`costMax` parsed as `Double` REJECTING NaN/Infinity;
 *    `dateFrom`/`dateTo` parsed as ISO-8601 `Instant`; `limit` defaults to
 *    [SpanRepository.DEFAULT_LIMIT] and is capped at the max (≤ 500); `offset` floored at 0;
 *    default ordering is newest-first.
 *  - outcome derivation: `RunSummary.outcome` is the list-scale proxy projected from
 *    `AgentRunRow.outcome`.
 *
 * References `RunListMapper`, `RunSummary`, `RunListResponse` — created by Plan 02, so this file
 * does not compile until then (intentional RED; NOT @Disabled). Block-body `@Test` returning Unit
 * (Pitfall 5).
 */
class RunListMapperTest {
    private val mapper = RunListMapper()

    @Test
    fun parsesAgentModelOutcomeAndCostRangeIntoFilter() {
        val filter =
            mapper.toFilter(
                mapOf(
                    "agent" to "planner-agent",
                    "model" to "claude-opus-4",
                    "outcome" to "SUCCESS",
                    "costMin" to "0.01",
                    "costMax" to "5.0",
                ),
            )
        filter.agentName shouldBe "planner-agent"
        filter.model shouldBe "claude-opus-4"
        filter.outcome shouldBe "SUCCESS"
        filter.costMin shouldBe 0.01
        filter.costMax shouldBe 5.0
    }

    @Test
    fun rejectsNonFiniteCostValues() {
        shouldThrow<IllegalArgumentException> { mapper.toFilter(mapOf("costMin" to "NaN")) }
        shouldThrow<IllegalArgumentException> { mapper.toFilter(mapOf("costMax" to "Infinity")) }
    }

    @Test
    fun parsesIsoDatesIntoInstants() {
        val filter =
            mapper.toFilter(
                mapOf(
                    "dateFrom" to "2026-06-01T00:00:00Z",
                    "dateTo" to "2026-06-30T23:59:59Z",
                ),
            )
        filter.dateFrom shouldBe Instant.parse("2026-06-01T00:00:00Z")
        filter.dateTo shouldBe Instant.parse("2026-06-30T23:59:59Z")
    }

    @Test
    fun limitDefaultsAndCapsAndOffsetFloorsAtZero() {
        // Omitted limit → DEFAULT_LIMIT; offset omitted → 0.
        mapper.limitOf(emptyMap()) shouldBe SpanRepository.DEFAULT_LIMIT
        mapper.offsetOf(emptyMap()) shouldBe 0

        // Over-cap limit clamps to the max (≤ 500); negative offset floors at 0.
        mapper.limitOf(mapOf("limit" to "100000")) shouldBeLessThanOrEqual 500
        mapper.offsetOf(mapOf("offset" to "-5")) shouldBe 0
    }

    @Test
    fun derivesRunSummaryOutcomeFromAgentRunRow() {
        val row =
            AgentRunRow(
                traceId = "summary-trace-0000000000000001",
                agentName = "planner-agent",
                model = "claude-opus-4",
                outcome = "ERROR",
                startTime = Instant.parse("2026-06-23T12:00:00Z"),
                endTime = Instant.parse("2026-06-23T12:00:05Z"),
                inputTokens = 1_200,
                outputTokens = 350,
                costUsd = 0.0425,
                maxSaturation = 62.5f,
                spanCount = 3,
            )
        val response: RunListResponse = mapper.toResponse(listOf(row))
        val summary: RunSummary = response.runs.single()
        summary.outcome shouldBe "ERROR"
        summary.traceId shouldBe "summary-trace-0000000000000001"
    }
}
