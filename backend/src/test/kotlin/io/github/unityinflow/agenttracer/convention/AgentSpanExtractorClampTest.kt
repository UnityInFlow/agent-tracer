package io.github.unityinflow.agenttracer.convention

import io.github.unityinflow.agentsemconv.UnityInFlowAttributes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `unityinflow.context.saturation_pct` clamps to [0,100] (D-04). */
class AgentSpanExtractorClampTest {
    private fun saturationOf(value: Double): Double? =
        AgentSpanExtractor
            .extract(
                attrs = SpanFixtures.view(SpanFixtures.doubleAttr(UnityInFlowAttributes.CONTEXT_SATURATION_PCT, value)),
                resource = AttrView.empty(),
            ).saturationPct

    @Test
    fun `over-range saturation clamps down to 100`() {
        saturationOf(137.0) shouldBe 100.0
    }

    @Test
    fun `negative saturation clamps up to 0`() {
        saturationOf(-5.0) shouldBe 0.0
    }

    @Test
    fun `in-range saturation is preserved`() {
        saturationOf(73.5) shouldBe 73.5
    }
}
