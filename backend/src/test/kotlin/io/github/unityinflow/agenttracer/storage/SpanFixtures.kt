package io.github.unityinflow.agenttracer.storage

import io.github.unityinflow.agentsemconv.GenAiOperation
import io.github.unityinflow.agenttracer.domain.AgentOutcome
import io.github.unityinflow.agenttracer.domain.AgentSpan
import java.time.Instant

/**
 * Deterministic `(AgentSpan, SpanContext)` test-data factories for every downstream storage test.
 *
 * Each fixture pairs an [AgentSpan] (OTLP *attributes*) with a [SpanContext] supplying the
 * envelope identity ([SpanContext.traceId]/[SpanContext.spanId]/[SpanContext.parentSpanId]/
 * [SpanContext.timestamp]) that [AgentSpan] omits — so every row written downstream has a
 * non-empty, known `(TraceId, SpanId)` identity (never an empty-string sentinel that would
 * collapse every span into one key/partition).
 *
 * The values are fixed and assertion-ready: token/cost/saturation numbers are known constants so
 * STOR-03 truncation round-trips and the agent_runs rollup math have exact expected outputs.
 * This file contains test-data factories only — no production code and no DB calls.
 */
object SpanFixtures {
    /** A fixed instant so partition/order assertions are reproducible. */
    val BASE_TIME: Instant = Instant.parse("2026-06-22T10:00:00Z")

    const val DEFAULT_TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
    const val DEFAULT_SPAN_ID = "b7ad6b7169203331"
    const val DEFAULT_PARENT_SPAN_ID = "a1b2c3d4e5f60718"

    /** Threshold above which a payload is considered "large" (256 KB) for the truncated variant. */
    const val PAYLOAD_CAP_BYTES = 256 * 1024

    /**
     * Every [AgentSpan] field populated with known values, paired with a fixed identity. The
     * canonical "rich span" for round-trip and rollup assertions.
     */
    fun full(
        traceId: String = DEFAULT_TRACE_ID,
        spanId: String = DEFAULT_SPAN_ID,
        parentSpanId: String = DEFAULT_PARENT_SPAN_ID,
        timestamp: Instant = BASE_TIME,
    ): Pair<AgentSpan, SpanContext> {
        val span =
            AgentSpan(
                agentName = "planner-agent",
                agentId = "agent-001",
                provider = "anthropic",
                model = "claude-opus-4",
                skillName = "code-review",
                toolCallId = "tool-call-42",
                conversationId = "conv-7",
                skillVersion = "1.2.0",
                inputTokens = 1_200L,
                outputTokens = 350L,
                costUsd = 0.0425,
                saturationPct = 62.5,
                planStep = 3L,
                operation = GenAiOperation.CHAT,
                outcome = AgentOutcome.SUCCESS,
                inputPayload = """{"messages":[{"role":"user","content":"review this"}]}""",
                outputPayload = """{"messages":[{"role":"assistant","content":"looks good"}]}""",
                systemInstructions = "You are a senior reviewer.",
                payloadTruncated = false,
                payloadOriginalSizeBytes = 137L,
                finishReasons = listOf("stop"),
                schemaVersion = "1.0",
            )
        return span to SpanContext(traceId, spanId, parentSpanId, timestamp)
    }

    /**
     * Only [AgentSpan.outcome] set; every nullable field is `null` (exercises D-01/D-02 absence).
     * Still paired with a real fixed [SpanContext] so the row identity is never empty.
     */
    fun minimal(
        traceId: String = DEFAULT_TRACE_ID,
        spanId: String = "c8be7c8278314442",
        parentSpanId: String = "",
        timestamp: Instant = BASE_TIME,
    ): Pair<AgentSpan, SpanContext> {
        val span =
            AgentSpan(
                agentName = null,
                agentId = null,
                provider = null,
                model = null,
                skillName = null,
                toolCallId = null,
                conversationId = null,
                skillVersion = null,
                inputTokens = null,
                outputTokens = null,
                costUsd = null,
                saturationPct = null,
                planStep = null,
                operation = null,
                outcome = AgentOutcome.SUCCESS,
                inputPayload = null,
                outputPayload = null,
                systemInstructions = null,
                payloadTruncated = null,
                payloadOriginalSizeBytes = null,
                finishReasons = null,
                schemaVersion = null,
            )
        return span to SpanContext(traceId, spanId, parentSpanId, timestamp)
    }

    /**
     * A truncated-payload span: [AgentSpan.payloadTruncated] = `true`, original size above
     * [PAYLOAD_CAP_BYTES], payload bodies sized at the cap — so STOR-03 truncation round-trip has
     * fixed expected values. Real fixed identity.
     */
    fun truncatedPayload(
        traceId: String = DEFAULT_TRACE_ID,
        spanId: String = "d9cf8d9389425553",
        parentSpanId: String = DEFAULT_PARENT_SPAN_ID,
        timestamp: Instant = BASE_TIME,
    ): Pair<AgentSpan, SpanContext> {
        val cappedBody = "x".repeat(PAYLOAD_CAP_BYTES)
        val span =
            AgentSpan(
                agentName = "planner-agent",
                agentId = "agent-001",
                provider = "anthropic",
                model = "claude-opus-4",
                skillName = "code-review",
                toolCallId = "tool-call-99",
                conversationId = "conv-7",
                skillVersion = "1.2.0",
                inputTokens = 50_000L,
                outputTokens = 4_000L,
                costUsd = 1.5,
                saturationPct = 99.9,
                planStep = 9L,
                operation = GenAiOperation.CHAT,
                outcome = AgentOutcome.SUCCESS,
                inputPayload = cappedBody,
                outputPayload = cappedBody,
                systemInstructions = "You are a senior reviewer.",
                payloadTruncated = true,
                payloadOriginalSizeBytes = (PAYLOAD_CAP_BYTES * 4).toLong(),
                finishReasons = listOf("length"),
                schemaVersion = "1.0",
            )
        return span to SpanContext(traceId, spanId, parentSpanId, timestamp)
    }

    /**
     * Mints [n] spans SHARING one [traceId] — distinct, deterministic SpanIds and monotonically
     * increasing timestamps (one second apart). [agentName] and [model] are set UNIFORMLY across
     * every span so the agent_runs rollup (`anyLast`) and the listRuns filter-by-agent/model
     * assertions are order-independent (anyLast determinism).
     *
     * The first span is the trace root (empty parentSpanId); each later span is a child of the
     * first, forming a simple parent→children shape with a single, stable agent/model per trace.
     */
    fun traceOf(
        n: Int,
        traceId: String = DEFAULT_TRACE_ID,
        agentName: String = "planner-agent",
        model: String = "claude-opus-4",
        baseTime: Instant = BASE_TIME,
    ): List<Pair<AgentSpan, SpanContext>> {
        require(n >= 1) { "traceOf requires at least one span, got $n" }
        val rootSpanId = "%016x".format(1L)
        return (0 until n).map { i ->
            val spanId = "%016x".format((i + 1).toLong())
            val parentSpanId = if (i == 0) "" else rootSpanId
            val (baseSpan, _) = full(traceId = traceId, spanId = spanId, parentSpanId = parentSpanId)
            val span =
                baseSpan.copy(
                    agentName = agentName,
                    model = model,
                    planStep = (i + 1).toLong(),
                )
            val ctx = SpanContext(traceId, spanId, parentSpanId, baseTime.plusSeconds(i.toLong()))
            span to ctx
        }
    }
}
