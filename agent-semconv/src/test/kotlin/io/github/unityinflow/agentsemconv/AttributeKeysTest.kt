package io.github.unityinflow.agentsemconv

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AttributeKeysTest {
    @Test
    fun `gen_ai key constants resolve to their exact OTel strings`() {
        GenAiAttributes.OPERATION_NAME shouldBe "gen_ai.operation.name"
        GenAiAttributes.PROVIDER_NAME shouldBe "gen_ai.provider.name"
        GenAiAttributes.REQUEST_MODEL shouldBe "gen_ai.request.model"
        GenAiAttributes.RESPONSE_MODEL shouldBe "gen_ai.response.model"
        GenAiAttributes.USAGE_INPUT_TOKENS shouldBe "gen_ai.usage.input_tokens"
        GenAiAttributes.USAGE_OUTPUT_TOKENS shouldBe "gen_ai.usage.output_tokens"
        GenAiAttributes.AGENT_NAME shouldBe "gen_ai.agent.name"
        GenAiAttributes.AGENT_ID shouldBe "gen_ai.agent.id"
        GenAiAttributes.TOOL_NAME shouldBe "gen_ai.tool.name"
        GenAiAttributes.TOOL_CALL_ID shouldBe "gen_ai.tool.call.id"
        GenAiAttributes.CONVERSATION_ID shouldBe "gen_ai.conversation.id"
        GenAiAttributes.RESPONSE_FINISH_REASONS shouldBe "gen_ai.response.finish_reasons"
        GenAiAttributes.INPUT_MESSAGES shouldBe "gen_ai.input.messages"
        GenAiAttributes.OUTPUT_MESSAGES shouldBe "gen_ai.output.messages"
        GenAiAttributes.SYSTEM_INSTRUCTIONS shouldBe "gen_ai.system_instructions"
    }

    @Test
    fun `service_name resource key resolves correctly`() {
        GenAiAttributes.SERVICE_NAME shouldBe "service.name"
    }

    @Test
    fun `unityinflow key constants resolve to their exact strings`() {
        UnityInFlowAttributes.SKILL_NAME shouldBe "unityinflow.skill.name"
        UnityInFlowAttributes.SKILL_VERSION shouldBe "unityinflow.skill.version"
        UnityInFlowAttributes.CONTEXT_SATURATION_PCT shouldBe "unityinflow.context.saturation_pct"
        UnityInFlowAttributes.COST_USD shouldBe "unityinflow.cost.usd"
        UnityInFlowAttributes.PLAN_STEP shouldBe "unityinflow.plan.step"
        UnityInFlowAttributes.OUTCOME shouldBe "unityinflow.outcome"
        UnityInFlowAttributes.SCHEMA_VERSION shouldBe "unityinflow.schema_version"
        UnityInFlowAttributes.PAYLOAD_TRUNCATED shouldBe "unityinflow.payload.truncated"
        UnityInFlowAttributes.PAYLOAD_ORIGINAL_SIZE_BYTES shouldBe "unityinflow.payload.original_size_bytes"
    }
}
