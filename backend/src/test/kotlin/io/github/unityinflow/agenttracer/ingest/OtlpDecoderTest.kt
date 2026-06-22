package io.github.unityinflow.agenttracer.ingest

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agenttracer.convention.SpanFixtures
import io.github.unityinflow.agenttracer.storage.SpanFixtures.DEFAULT_SPAN_ID
import io.github.unityinflow.agenttracer.storage.SpanFixtures.DEFAULT_TRACE_ID
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit proof for the transport-agnostic [OtlpDecoder] covering D-06 (single decode path),
 * D-02 (tolerant per-span rejection, no size check), D-03 (store-all unified path), and the
 * load-bearing D-07 hex round-trip against the Phase-2 `storage/SpanFixtures` constants.
 *
 * Every `@Test` uses a block body (`{ ... }`), never an expression body returning a Kotest
 * matcher — the latter is silently SKIPPED by JUnit (Pitfall 5, the Phase-2 lesson). The
 * suite asserts 0 skipped at the phase gate.
 */
class OtlpDecoderTest {
    private val decoder = OtlpDecoder()

    private val parentHex = "a1b2c3d4e5f60718"

    /** A request carrying a single well-formed span with the given ids/attrs. */
    private fun requestOf(
        traceHex: String = DEFAULT_TRACE_ID,
        spanHex: String = DEFAULT_SPAN_ID,
        parentHex: String = "",
        startUnixNano: Long = 0L,
        attrs: List<io.opentelemetry.proto.common.v1.KeyValue> = emptyList(),
        resourceAttrs: List<io.opentelemetry.proto.common.v1.KeyValue>? = null,
        isError: Boolean = false,
    ) = OtlpRequestFixtures.exportRequest(
        OtlpRequestFixtures.resourceSpans(
            resourceAttrs = resourceAttrs,
            spans =
                listOf(
                    OtlpRequestFixtures.spanProto(traceHex, spanHex, parentHex, startUnixNano, attrs, isError),
                ),
        ),
    )

    @Test
    fun wellFormedSpanDecodesToExactlyOneAcceptedEnvelope() {
        val result = decoder.decode(requestOf())
        result.accepted shouldHaveSize 1
        result.rejectedSpans shouldBe 0L
        result.errorMessage.shouldBeNull()
    }

    @Test
    fun decodedTraceIdRoundTripsToPhase2HexConstantByteForByte() {
        // D-07: build the bytes FROM the Phase-2 hex constant, decode, assert the same lowercase hex back.
        val ctx =
            decoder
                .decode(requestOf())
                .accepted
                .single()
                .context
        ctx.traceId shouldBe DEFAULT_TRACE_ID
        ctx.traceId.length shouldBe 32
        ctx.spanId shouldBe DEFAULT_SPAN_ID
        ctx.spanId.length shouldBe 16
    }

    @Test
    fun emptyParentSpanIdDecodesToEmptyStringNotNullNorZeroes() {
        val ctx =
            decoder
                .decode(requestOf(parentHex = ""))
                .accepted
                .single()
                .context
        ctx.parentSpanId shouldBe ""
    }

    @Test
    fun presentParentSpanIdDecodesToItsLowercaseHex() {
        val ctx =
            decoder
                .decode(requestOf(parentHex = parentHex))
                .accepted
                .single()
                .context
        ctx.parentSpanId shouldBe parentHex
    }

    @Test
    fun timestampIsTheSpanStartTimeFromUnixNano() {
        val startNano = 1_700_000_000_123_456_789L
        val ctx =
            decoder
                .decode(requestOf(startUnixNano = startNano))
                .accepted
                .single()
                .context
        ctx.timestamp shouldBe Instant.ofEpochSecond(0, startNano)
    }

    @Test
    fun spanWithEmptyTraceIdIsRejectedNotAccepted() {
        val result = decoder.decode(requestOf(traceHex = ""))
        result.accepted shouldHaveSize 0
        result.rejectedSpans shouldBe 1L
        result.errorMessage.shouldNotBeNull()
    }

    @Test
    fun spanWithEmptySpanIdIsRejectedNotAccepted() {
        val result = decoder.decode(requestOf(spanHex = ""))
        result.accepted shouldHaveSize 0
        result.rejectedSpans shouldBe 1L
        result.errorMessage.shouldNotBeNull()
    }

    @Test
    fun oneBadSpanAmongGoodOnesDoesNotDropTheGoodOnes() {
        // Tolerant per-span (D-02): a good span, a bad (empty-trace) span, another good span.
        val good1 = OtlpRequestFixtures.spanProto(DEFAULT_TRACE_ID, "0000000000000001")
        val bad = OtlpRequestFixtures.spanProto("", "0000000000000002")
        val good2 = OtlpRequestFixtures.spanProto(DEFAULT_TRACE_ID, "0000000000000003")
        val request =
            OtlpRequestFixtures.exportRequest(
                OtlpRequestFixtures.resourceSpans(null, listOf(good1, bad, good2)),
            )
        val result = decoder.decode(request)
        result.accepted shouldHaveSize 2
        result.rejectedSpans shouldBe 1L
        result.errorMessage.shouldNotBeNull()
    }

    @Test
    fun agentSpanExtractorBridgeIsWiredSoGenAiTokensPopulate() {
        val attrs = listOf(SpanFixtures.intAttr(GenAiAttributes.USAGE_INPUT_TOKENS, 1234L))
        val envelope = decoder.decode(requestOf(attrs = attrs)).accepted.single()
        envelope.span.inputTokens shouldBe 1234L
    }

    @Test
    fun nonAgentSpanIsAcceptedThroughTheSamePathWithAgentFieldsDefaulted() {
        // D-03 store-all: a plain HTTP/DB span (no gen_ai.* attrs) is accepted, not dropped.
        val attrs = listOf(SpanFixtures.stringAttr("http.request.method", "GET"))
        val result = decoder.decode(requestOf(attrs = attrs))
        result.accepted shouldHaveSize 1
        result.rejectedSpans shouldBe 0L
        val span = result.accepted.single().span
        span.inputTokens.shouldBeNull()
        span.agentName.shouldBeNull()
        span.model.shouldBeNull()
    }

    @Test
    fun resourceServiceNameFallsBackToAgentNameForNonAgentSpan() {
        // Resource attrs flow through to the extractor's D-10 fallback (proves resourceView is wired).
        val ctxAttrs = listOf(SpanFixtures.stringAttr(GenAiAttributes.SERVICE_NAME, "kore-runtime"))
        val span =
            decoder
                .decode(requestOf(resourceAttrs = ctxAttrs))
                .accepted
                .single()
                .span
        span.agentName shouldBe "kore-runtime"
    }

    @Test
    fun multiMegabyteStringAttributeSpanIsStillAcceptedNoSizeCheck() {
        // D-02: the decoder applies NO size check; truncation is downstream in AgentSpanRowMapper.
        val bigPrompt = "x".repeat(3 * 1024 * 1024)
        val attrs = listOf(SpanFixtures.stringAttr(GenAiAttributes.INPUT_MESSAGES, bigPrompt))
        val result = decoder.decode(requestOf(attrs = attrs))
        result.accepted shouldHaveSize 1
        result.rejectedSpans shouldBe 0L
    }

    @Test
    fun resourceSpansWithNoResourceUsesEmptyViewAndDoesNotThrow() {
        // resourceAttrs = null ⇒ no resource set ⇒ AttrView.empty(); must decode cleanly.
        val result = decoder.decode(requestOf(resourceAttrs = null))
        result.accepted shouldHaveSize 1
        result.accepted
            .single()
            .span.agentName
            .shouldBeNull()
    }

    @Test
    fun errorStatusDrivesOutcomeThroughTheExtractor() {
        val span =
            decoder
                .decode(requestOf(isError = true))
                .accepted
                .single()
                .span
        span.outcome shouldBe io.github.unityinflow.agenttracer.domain.AgentOutcome.FAILURE
    }

    @Test
    fun decodeIsDeterministicForTheSameRequest() {
        val request = requestOf()
        val a = decoder.decode(request)
        val b = decoder.decode(request)
        a.accepted.map { it.context } shouldBe b.accepted.map { it.context }
        a.rejectedSpans shouldBe b.rejectedSpans
    }
}
