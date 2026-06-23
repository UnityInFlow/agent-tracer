package io.github.unityinflow.agenttracer.demo

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agentsemconv.UnityInFlowAttributes
import io.kotest.matchers.collections.shouldContainAnyOf
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Determinism + full-convention assertions for [SeededRunFactory] (SC4).
 *
 * Uses an [InMemorySpanExporter] so the captured span trees are comparable
 * in-process with no live OTLP receiver. The OTel SDK exports finished spans
 * in END order; for two same-seed runs the END order is identical, so a
 * normalized projection (name + attribute map) compares equal.
 */
class SeededRunFactoryTest {
    private fun capture(
        seed: Long,
        runs: Int,
    ): List<SpanData> {
        val exporter = InMemorySpanExporter.create()
        val provider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        try {
            val tracer = provider.get("demo-emitter-test")
            val factory = SeededRunFactory(Random(seed))
            repeat(runs) { factory.generateRun(tracer) }
            provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS)
            return exporter.finishedSpanItems.toList()
        } finally {
            provider.close()
            exporter.close()
        }
    }

    /** A name + attribute-map projection that ignores ids/timestamps (which vary per run). */
    private fun project(spans: List<SpanData>): List<Pair<String, Map<String, Any>>> =
        spans.map { span ->
            span.name to
                span.attributes
                    .asMap()
                    .entries
                    .associate { (key, value) -> key.key to value }
        }

    @Test
    fun sameSeedProducesIdenticalSpanTrees() {
        val first = capture(seed = 1234L, runs = 4)
        val second = capture(seed = 1234L, runs = 4)

        // Same count and same normalized (name, attributes) sequence.
        second.size shouldBe first.size
        project(second) shouldBe project(first)
    }

    @Test
    fun differentSeedsDiverge() {
        val a = project(capture(seed = 1L, runs = 4))
        val b = project(capture(seed = 999L, runs = 4))
        // Overwhelmingly likely to differ; guards against a constant (ignored-rng) bug.
        (a == b) shouldBe false
    }

    @Test
    fun everyRunExercisesTheFullConvention() {
        val spans = capture(seed = 42L, runs = 1)

        val saturationKey = AttributeKey.doubleKey(UnityInFlowAttributes.CONTEXT_SATURATION_PCT)
        val skillKey = AttributeKey.stringKey(UnityInFlowAttributes.SKILL_NAME)
        val costKey = AttributeKey.doubleKey(UnityInFlowAttributes.COST_USD)
        val toolKey = AttributeKey.stringKey(GenAiAttributes.TOOL_NAME)

        // ≥1 span carries each load-bearing convention attribute.
        spans.count { it.attributes.get(saturationKey) != null } shouldBeGreaterThanOrEqualTo 1
        spans.count { it.attributes.get(skillKey) != null } shouldBeGreaterThanOrEqualTo 3
        spans.count { it.attributes.get(costKey) != null } shouldBe spans.size
        spans.count { it.attributes.get(toolKey) != null } shouldBeGreaterThanOrEqualTo 1

        // ≥2 nested invoke_agent levels: the root + at least one nested subagent below the plan.
        spans.count { it.name == ScenarioNode.OP_INVOKE_AGENT } shouldBeGreaterThanOrEqualTo 2
        spans.count { it.name == ScenarioNode.OP_PLAN } shouldBeGreaterThanOrEqualTo 1

        // Saturation is a RISING series (Pitfall 6): samples in END order are non-decreasing
        // is not guaranteed (END is post-order), but the set of samples spans a rising range.
        val saturations = spans.mapNotNull { it.attributes.get(saturationKey) }
        saturations.max() shouldBeGreaterThanOrEqualTo saturations.min()
        saturations.size shouldBeGreaterThanOrEqualTo 1
    }

    @Test
    fun outcomesVaryAcrossRuns() {
        val spans = capture(seed = 7L, runs = 12)
        val outcomeKey = AttributeKey.stringKey(UnityInFlowAttributes.OUTCOME)
        val outcomes = spans.mapNotNull { it.attributes.get(outcomeKey) }.toSet()
        // The factory mixes success/failure/partial across runs.
        outcomes.size shouldBeGreaterThanOrEqualTo 2
        outcomes shouldContainAnyOf listOf("success", "failure", "partial")
    }
}
