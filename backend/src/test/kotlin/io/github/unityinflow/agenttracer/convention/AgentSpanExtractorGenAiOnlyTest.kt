package io.github.unityinflow.agenttracer.convention

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agentsemconv.GenAiOperation
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A span carrying only standard `gen_ai.*` attributes extracts into typed agent
 * fields with ALL `unityinflow.*` fields null (CONV-02, success criterion 3).
 */
class AgentSpanExtractorGenAiOnlyTest {
    private val span =
        AgentSpanExtractor.extract(
            attrs = AttrView.of(SpanFixtures.genAiOnlyAttrs),
            resource = AttrView.of(SpanFixtures.resourceAttrs),
        )

    @Test
    fun `agent identity comes from gen_ai agent name`() {
        span.agentName shouldBe "research-agent"
    }

    @Test
    fun `model prefers response model over request model`() {
        span.model shouldBe "claude-opus-4"
    }

    @Test
    fun `tokens come from gen_ai usage attributes`() {
        span.inputTokens shouldBe 1234L
        span.outputTokens shouldBe 567L
    }

    @Test
    fun `operation parsed from gen_ai operation name`() {
        span.operation shouldBe GenAiOperation.INVOKE_AGENT
    }

    @Test
    fun `skill falls back to gen_ai tool name when no unityinflow skill`() {
        span.skillName shouldBe "web_search"
    }

    @Test
    fun `all unityinflow-only fields are null on a gen_ai-only span`() {
        span.costUsd.shouldBeNull()
        span.saturationPct.shouldBeNull()
        span.skillVersion.shouldBeNull()
        span.planStep.shouldBeNull()
        span.payloadTruncated.shouldBeNull()
        span.payloadOriginalSizeBytes.shouldBeNull()
        span.schemaVersion.shouldBeNull()
    }

    @Test
    fun `model falls back to request model when response model absent`() {
        val span =
            AgentSpanExtractor.extract(
                attrs = SpanFixtures.view(SpanFixtures.stringAttr(GenAiAttributes.REQUEST_MODEL, "gpt-5")),
                resource = AttrView.empty(),
            )
        span.model shouldBe "gpt-5"
    }

    @Test
    fun `agent identity falls back to resource service name when gen_ai agent name absent`() {
        val span =
            AgentSpanExtractor.extract(
                attrs = AttrView.empty(),
                resource = AttrView.of(SpanFixtures.resourceAttrs),
            )
        span.agentName shouldBe "kore-runtime"
    }
}
