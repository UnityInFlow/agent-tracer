package io.github.unityinflow.agenttracer.convention

import io.github.unityinflow.agenttracer.domain.AgentOutcome
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Missing fields extract as null — no sentinel defaults (D-12) — and the
 * extractor never throws on an empty or malformed attribute list (Security V5,
 * T-01-03).
 */
class AgentSpanExtractorNullableTest {
    @Test
    fun `a span missing cost saturation and skill extracts those fields as null`() {
        val span = AgentSpanExtractor.extract(AttrView.empty(), AttrView.empty())
        span.costUsd.shouldBeNull()
        span.saturationPct.shouldBeNull()
        span.skillName.shouldBeNull()
        span.inputTokens.shouldBeNull()
        span.outputTokens.shouldBeNull()
        span.planStep.shouldBeNull()
    }

    @Test
    fun `extracting from an empty attribute list never throws and outcome defaults to success`() {
        val span = AgentSpanExtractor.extract(AttrView.empty(), AttrView.empty())
        span.outcome shouldBe AgentOutcome.SUCCESS
    }

    @Test
    fun `wrong OTLP variants yield null fields rather than throwing`() {
        // cost sent as a string, tokens sent as a double — both wrong variants.
        val span =
            AgentSpanExtractor.extract(
                attrs =
                    SpanFixtures.view(
                        SpanFixtures.stringAttr("unityinflow.cost.usd", "free"),
                        SpanFixtures.doubleAttr("gen_ai.usage.input_tokens", 12.0),
                    ),
                resource = AttrView.empty(),
            )
        span.costUsd.shouldBeNull()
        span.inputTokens.shouldBeNull()
    }
}
