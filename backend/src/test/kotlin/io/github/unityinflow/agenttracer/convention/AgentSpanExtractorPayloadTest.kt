package io.github.unityinflow.agenttracer.convention

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Payloads are carried verbatim (never parsed); the truncation contract is read
 * from `unityinflow.payload.*`; payload fields are opt-in and null when absent
 * (Pitfall 4, D-13/D-14).
 */
class AgentSpanExtractorPayloadTest {
    private val full =
        AgentSpanExtractor.extract(
            attrs = AttrView.of(SpanFixtures.fullAttrs),
            resource = AttrView.of(SpanFixtures.resourceAttrs),
        )

    @Test
    fun `input and output payloads are carried verbatim`() {
        full.inputPayload shouldBe """[{"role":"user","content":"hi"}]"""
        full.outputPayload shouldBe """[{"role":"assistant","content":"hello"}]"""
        full.systemInstructions shouldBe "You are helpful."
    }

    @Test
    fun `payload truncation contract is read from unityinflow payload attributes`() {
        full.payloadTruncated shouldBe true
        full.payloadOriginalSizeBytes shouldBe 524288L
    }

    @Test
    fun `finish reasons array is carried as a string list`() {
        full.finishReasons shouldBe listOf("stop", "length")
    }

    @Test
    fun `a span with no payload attributes yields null payload fields`() {
        val span = AgentSpanExtractor.extract(AttrView.empty(), AttrView.empty())
        span.inputPayload.shouldBeNull()
        span.outputPayload.shouldBeNull()
        span.systemInstructions.shouldBeNull()
        span.payloadTruncated.shouldBeNull()
        span.payloadOriginalSizeBytes.shouldBeNull()
        span.finishReasons.shouldBeNull()
    }
}
