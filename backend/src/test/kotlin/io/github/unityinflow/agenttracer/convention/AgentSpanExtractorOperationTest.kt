package io.github.unityinflow.agenttracer.convention

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agentsemconv.GenAiOperation
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `gen_ai.operation.name` parses into the typed operation enum; unknown → null. */
class AgentSpanExtractorOperationTest {
    private fun operationOf(value: String): GenAiOperation? =
        AgentSpanExtractor
            .extract(
                attrs = SpanFixtures.view(SpanFixtures.stringAttr(GenAiAttributes.OPERATION_NAME, value)),
                resource = AttrView.empty(),
            ).operation

    @Test
    fun `invoke_agent yields INVOKE_AGENT`() {
        operationOf("invoke_agent") shouldBe GenAiOperation.INVOKE_AGENT
    }

    @Test
    fun `execute_tool yields EXECUTE_TOOL`() {
        operationOf("execute_tool") shouldBe GenAiOperation.EXECUTE_TOOL
    }

    @Test
    fun `an unknown operation yields null without throwing`() {
        operationOf("teleport_agent").shouldBeNull()
    }

    @Test
    fun `a span with no operation attribute yields null operation`() {
        AgentSpanExtractor.extract(AttrView.empty(), AttrView.empty()).operation.shouldBeNull()
    }
}
