package io.github.unityinflow.agenttracer.storage

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure-JVM unit test for [AgentSpanRowMapper] (no Docker, no coroutines).
 *
 * Proves the Task-1 contract:
 *  - SpanRow/PayloadRow identity (TraceId/SpanId/ParentSpanId/Timestamp) is sourced from the
 *    [SpanContext] argument and is never empty/null.
 *  - A payload field over the 256 KB byte cap is truncated to the cap, with PayloadTruncated=1
 *    and PayloadOriginalSizeBytes = the original BYTE length (Phase-1 D-13).
 *  - A payload field at/under the cap is carried verbatim with PayloadTruncated=0.
 *  - Null AgentSpan metric fields stay null in SpanRow (D-01); null string dimensions become ''
 *    (D-02).
 */
class AgentSpanRowMapperTest {
    private val mapper = AgentSpanRowMapper()

    @Test
    fun identityColumnsComeFromSpanContext() {
        val (span, _) = SpanFixtures.full()
        val ctx =
            SpanContext(
                traceId = "trace-abc",
                spanId = "span-def",
                parentSpanId = "parent-ghi",
                timestamp = Instant.parse("2026-06-22T08:00:00Z"),
            )

        val (spanRow, payloadRow) = mapper.map(span, ctx)

        spanRow.traceId shouldBe "trace-abc"
        spanRow.spanId shouldBe "span-def"
        spanRow.parentSpanId shouldBe "parent-ghi"
        spanRow.timestamp shouldBe Instant.parse("2026-06-22T08:00:00Z")
        // PayloadRow shares the (TraceId, SpanId, Timestamp) identity.
        payloadRow.traceId shouldBe "trace-abc"
        payloadRow.spanId shouldBe "span-def"
        payloadRow.timestamp shouldBe Instant.parse("2026-06-22T08:00:00Z")
    }

    @Test
    fun fullSpanProjectsTypedDimensionsAndMetrics() {
        val (span, ctx) = SpanFixtures.full()

        val (spanRow, _) = mapper.map(span, ctx)

        spanRow.agentName shouldBe "planner-agent"
        spanRow.model shouldBe "claude-opus-4"
        spanRow.skillName shouldBe "code-review"
        spanRow.operation shouldBe "CHAT"
        spanRow.outcome shouldBe "SUCCESS"
        spanRow.finishReasons shouldBe listOf("stop")
        spanRow.inputTokens shouldBe 1_200L
        spanRow.outputTokens shouldBe 350L
        spanRow.costUsd shouldBe 0.0425
        spanRow.contextSaturationPct shouldBe 62.5f
        spanRow.planStep shouldBe 3L
    }

    @Test
    fun nullMetricsStayNullAndNullDimensionsBecomeSentinel() {
        val (span, ctx) = SpanFixtures.minimal()

        val (spanRow, payloadRow) = mapper.map(span, ctx)

        // D-01: null metric columns stay null (absence is information).
        spanRow.inputTokens.shouldBeNull()
        spanRow.outputTokens.shouldBeNull()
        spanRow.costUsd.shouldBeNull()
        spanRow.contextSaturationPct.shouldBeNull()
        spanRow.planStep.shouldBeNull()
        // D-02: null string dimensions become the '' sentinel.
        spanRow.agentName shouldBe ""
        spanRow.model shouldBe ""
        spanRow.skillName shouldBe ""
        spanRow.finishReasons shouldBe emptyList()
        // Identity is still real (never empty) — sourced from the context.
        spanRow.traceId shouldBe ctx.traceId
        spanRow.spanId shouldBe ctx.spanId
        // Null payload bodies collapse to '' and are not truncated.
        payloadRow.inputPayload shouldBe ""
        payloadRow.outputPayload shouldBe ""
        payloadRow.payloadTruncated shouldBe false
        payloadRow.payloadOriginalSizeBytes.shouldBeNull()
    }

    @Test
    fun payloadUnderCapIsCarriedVerbatim() {
        val body = "y".repeat(1_000) // 1000 ASCII bytes, well under 256 KB
        val (base, ctx) = SpanFixtures.minimal()
        val span = base.copy(inputPayload = body, outputPayload = body)

        val (spanRow, payloadRow) = mapper.map(span, ctx)

        payloadRow.inputPayload shouldBe body
        payloadRow.outputPayload shouldBe body
        payloadRow.payloadTruncated shouldBe false
        payloadRow.payloadOriginalSizeBytes.shouldBeNull()
        spanRow.payloadTruncated shouldBe false
    }

    @Test
    fun oversizedPayloadIsTruncatedToCapWithMetadata() {
        // 300 KB of ASCII → 300 KB bytes, over the 256 KB cap.
        val original = "z".repeat(300 * 1024)
        val (base, ctx) = SpanFixtures.minimal()
        val span = base.copy(inputPayload = original)

        val (spanRow, payloadRow) = mapper.map(span, ctx)

        // Truncated to the 256 KB byte cap (ASCII: 1 byte == 1 char).
        payloadRow.inputPayload.toByteArray(Charsets.UTF_8).size shouldBe AgentSpanRowMapper.PAYLOAD_CAP_BYTES
        payloadRow.payloadTruncated shouldBe true
        payloadRow.payloadOriginalSizeBytes shouldBe (300 * 1024).toLong()
        // The flag propagates to the SpanRow truncation metadata too.
        spanRow.payloadTruncated shouldBe true
        spanRow.payloadOriginalSizeBytes shouldBe (300 * 1024).toLong()
    }

    @Test
    fun emitterPreTruncatedOriginalSizeIsPreserved() {
        // Emitter already truncated to the cap and recorded a far larger original size.
        val cappedBody = "x".repeat(AgentSpanRowMapper.PAYLOAD_CAP_BYTES)
        val (base, ctx) = SpanFixtures.minimal()
        val span =
            base.copy(
                inputPayload = cappedBody,
                payloadTruncated = true,
                payloadOriginalSizeBytes = 10_000_000L,
            )

        val (_, payloadRow) = mapper.map(span, ctx)

        payloadRow.payloadTruncated shouldBe true
        // The larger emitter-reported original size must NOT be lost or overwritten by the
        // local cap-length measurement.
        payloadRow.payloadOriginalSizeBytes shouldBe 10_000_000L
        payloadRow.inputPayload.toByteArray(Charsets.UTF_8).size shouldBe AgentSpanRowMapper.PAYLOAD_CAP_BYTES
    }

    @Test
    fun multiByteCharactersAreTruncatedOnByteBoundary() {
        // '€' is 3 UTF-8 bytes. Build a string whose byte length exceeds the cap.
        val euro = "€"
        val count = (AgentSpanRowMapper.PAYLOAD_CAP_BYTES / 3) + 100
        val original = euro.repeat(count)
        val (base, ctx) = SpanFixtures.minimal()
        val span = base.copy(outputPayload = original)

        val (_, payloadRow) = mapper.map(span, ctx)

        val truncatedBytes = payloadRow.outputPayload.toByteArray(Charsets.UTF_8).size
        // Never exceeds the cap, and never splits a multi-byte char (no replacement chars).
        (truncatedBytes <= AgentSpanRowMapper.PAYLOAD_CAP_BYTES) shouldBe true
        payloadRow.outputPayload.contains('�') shouldBe false
        payloadRow.payloadTruncated shouldBe true
        payloadRow.payloadOriginalSizeBytes shouldBe original.toByteArray(Charsets.UTF_8).size.toLong()
    }
}
