package io.github.unityinflow.agenttracer.convention

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agentsemconv.UnityInFlowAttributes
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * AttrView preserves OTLP value types per attribute and returns null on the
 * wrong-variant / absent paths (CONV-03, RESEARCH OTLP Value-Type Mapping).
 */
class AgentSpanExtractorValueTypeTest {
    @Test
    fun `int_value reads as Long, not the string 1234`() {
        val view = SpanFixtures.view(SpanFixtures.intAttr(GenAiAttributes.USAGE_INPUT_TOKENS, 1234L))
        view.long(GenAiAttributes.USAGE_INPUT_TOKENS) shouldBe 1234L
    }

    @Test
    fun `double_value reads as Double`() {
        val view = SpanFixtures.view(SpanFixtures.doubleAttr(UnityInFlowAttributes.COST_USD, 0.05))
        view.double(UnityInFlowAttributes.COST_USD) shouldBe 0.05
    }

    @Test
    fun `bool_value reads as Boolean`() {
        val view = SpanFixtures.view(SpanFixtures.boolAttr(UnityInFlowAttributes.PAYLOAD_TRUNCATED, true))
        view.bool(UnityInFlowAttributes.PAYLOAD_TRUNCATED) shouldBe true
    }

    @Test
    fun `array_value of strings reads as List of String`() {
        val view =
            SpanFixtures.view(
                SpanFixtures.stringArrayAttr(GenAiAttributes.RESPONSE_FINISH_REASONS, listOf("stop", "length")),
            )
        view.stringList(GenAiAttributes.RESPONSE_FINISH_REASONS) shouldBe listOf("stop", "length")
    }

    @Test
    fun `a string-typed value requested as long returns null (no lexical parse)`() {
        val view = SpanFixtures.view(SpanFixtures.stringAttr(GenAiAttributes.USAGE_INPUT_TOKENS, "1234"))
        view.long(GenAiAttributes.USAGE_INPUT_TOKENS).shouldBeNull()
    }

    @Test
    fun `absent keys return null for every accessor`() {
        val view = AttrView.empty()
        view.string("missing").shouldBeNull()
        view.long("missing").shouldBeNull()
        view.double("missing").shouldBeNull()
        view.bool("missing").shouldBeNull()
        view.int("missing").shouldBeNull()
        view.stringList("missing").shouldBeNull()
    }

    @Test
    fun `plan_step preserves the full int64 range as Long`() {
        // unityinflow.plan.step is int64 in the contract — an int64 value above
        // Int.MAX_VALUE must round-trip as a Long, NOT be silently dropped to null.
        val inRange = SpanFixtures.view(SpanFixtures.intAttr(UnityInFlowAttributes.PLAN_STEP, 3L))
        inRange.long(UnityInFlowAttributes.PLAN_STEP) shouldBe 3L

        val aboveIntRange =
            SpanFixtures.view(SpanFixtures.intAttr(UnityInFlowAttributes.PLAN_STEP, Long.MAX_VALUE))
        aboveIntRange.long(UnityInFlowAttributes.PLAN_STEP) shouldBe Long.MAX_VALUE
    }

    @Test
    fun `int accessor narrows int64 to Int and rejects out-of-range`() {
        val key = "test.int32.key"
        val inRange = SpanFixtures.view(SpanFixtures.intAttr(key, 3L))
        inRange.int(key) shouldBe 3

        val tooBig = SpanFixtures.view(SpanFixtures.intAttr(key, Long.MAX_VALUE))
        tooBig.int(key).shouldBeNull()
    }
}
