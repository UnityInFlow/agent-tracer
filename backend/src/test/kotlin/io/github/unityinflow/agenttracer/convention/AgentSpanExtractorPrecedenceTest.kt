package io.github.unityinflow.agenttracer.convention

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agentsemconv.UnityInFlowAttributes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * When `unityinflow.*` and `gen_ai.*` supply the same logical field and disagree,
 * `unityinflow.*` wins (D-11, roadmap success criterion 4).
 */
class AgentSpanExtractorPrecedenceTest {
    @Test
    fun `unityinflow skill name wins over gen_ai tool name on conflict`() {
        val span =
            AgentSpanExtractor.extract(
                attrs =
                    SpanFixtures.view(
                        SpanFixtures.stringAttr(UnityInFlowAttributes.SKILL_NAME, "deep-research"),
                        SpanFixtures.stringAttr(GenAiAttributes.TOOL_NAME, "web_search"),
                    ),
                resource = AttrView.empty(),
            )
        span.skillName shouldBe "deep-research"
    }

    @Test
    fun `unityinflow outcome overrides OTel error status`() {
        val span =
            AgentSpanExtractor.extract(
                attrs = SpanFixtures.view(SpanFixtures.stringAttr(UnityInFlowAttributes.OUTCOME, "partial")),
                resource = AttrView.empty(),
                otelStatusIsError = true,
            )
        span.outcome shouldBe io.github.unityinflow.agenttracer.domain.AgentOutcome.PARTIAL
    }

    @Test
    fun `full fixture span resolves skill from unityinflow not the tool name`() {
        val span =
            AgentSpanExtractor.extract(
                attrs = AttrView.of(SpanFixtures.fullAttrs),
                resource = AttrView.of(SpanFixtures.resourceAttrs),
            )
        span.skillName shouldBe "deep-research"
    }
}
