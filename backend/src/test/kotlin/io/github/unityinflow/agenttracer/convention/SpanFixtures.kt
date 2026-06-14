package io.github.unityinflow.agenttracer.convention

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agentsemconv.UnityInFlowAttributes
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.ArrayValue
import io.opentelemetry.proto.common.v1.KeyValue

/**
 * Reusable OTLP attribute builders and golden span fixtures shared across the
 * extractor test suite (VALIDATION Wave 0 fixtures).
 */
object SpanFixtures {
    fun stringAttr(
        key: String,
        value: String,
    ): KeyValue =
        KeyValue
            .newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setStringValue(value).build())
            .build()

    fun intAttr(
        key: String,
        value: Long,
    ): KeyValue =
        KeyValue
            .newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setIntValue(value).build())
            .build()

    fun doubleAttr(
        key: String,
        value: Double,
    ): KeyValue =
        KeyValue
            .newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setDoubleValue(value).build())
            .build()

    fun boolAttr(
        key: String,
        value: Boolean,
    ): KeyValue =
        KeyValue
            .newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setBoolValue(value).build())
            .build()

    fun stringArrayAttr(
        key: String,
        values: List<String>,
    ): KeyValue {
        val array =
            ArrayValue
                .newBuilder()
                .addAllValues(values.map { AnyValue.newBuilder().setStringValue(it).build() })
                .build()
        return KeyValue
            .newBuilder()
            .setKey(key)
            .setValue(AnyValue.newBuilder().setArrayValue(array).build())
            .build()
    }

    /** Wraps a KeyValue list into an [AttrView]. */
    fun view(vararg attrs: KeyValue): AttrView = AttrView.of(attrs.toList())

    /**
     * GOLDEN span: standard `gen_ai.*` attributes ONLY (no `unityinflow.*`).
     * Proves any gen_ai-emitting source renders usefully unchanged (CONV-02).
     */
    val genAiOnlyAttrs: List<KeyValue> =
        listOf(
            stringAttr(GenAiAttributes.AGENT_NAME, "research-agent"),
            stringAttr(GenAiAttributes.RESPONSE_MODEL, "claude-opus-4"),
            stringAttr(GenAiAttributes.REQUEST_MODEL, "claude-opus-4-requested"),
            stringAttr(GenAiAttributes.OPERATION_NAME, "invoke_agent"),
            stringAttr(GenAiAttributes.TOOL_NAME, "web_search"),
            intAttr(GenAiAttributes.USAGE_INPUT_TOKENS, 1234L),
            intAttr(GenAiAttributes.USAGE_OUTPUT_TOKENS, 567L),
            stringArrayAttr(GenAiAttributes.RESPONSE_FINISH_REASONS, listOf("stop")),
        )

    /** Resource attributes carrying `service.name` for the D-10 agent-identity fallback. */
    val resourceAttrs: List<KeyValue> =
        listOf(stringAttr(GenAiAttributes.SERVICE_NAME, "kore-runtime"))

    /**
     * FULL span: every `unityinflow.*` extension present alongside `gen_ai.*`,
     * with conflicting values to exercise precedence (D-11).
     */
    val fullAttrs: List<KeyValue> =
        listOf(
            stringAttr(GenAiAttributes.AGENT_NAME, "research-agent"),
            stringAttr(GenAiAttributes.RESPONSE_MODEL, "claude-opus-4"),
            stringAttr(GenAiAttributes.OPERATION_NAME, "execute_tool"),
            stringAttr(GenAiAttributes.TOOL_NAME, "web_search"),
            stringAttr(UnityInFlowAttributes.SKILL_NAME, "deep-research"),
            stringAttr(UnityInFlowAttributes.SKILL_VERSION, "1.2.0"),
            intAttr(GenAiAttributes.USAGE_INPUT_TOKENS, 1234L),
            intAttr(GenAiAttributes.USAGE_OUTPUT_TOKENS, 567L),
            doubleAttr(UnityInFlowAttributes.COST_USD, 0.05),
            doubleAttr(UnityInFlowAttributes.CONTEXT_SATURATION_PCT, 73.5),
            intAttr(UnityInFlowAttributes.PLAN_STEP, 3L),
            stringAttr(UnityInFlowAttributes.OUTCOME, "partial"),
            stringAttr(UnityInFlowAttributes.SCHEMA_VERSION, "1.0"),
            stringAttr(GenAiAttributes.INPUT_MESSAGES, """[{"role":"user","content":"hi"}]"""),
            stringAttr(GenAiAttributes.OUTPUT_MESSAGES, """[{"role":"assistant","content":"hello"}]"""),
            stringAttr(GenAiAttributes.SYSTEM_INSTRUCTIONS, "You are helpful."),
            boolAttr(UnityInFlowAttributes.PAYLOAD_TRUNCATED, true),
            intAttr(UnityInFlowAttributes.PAYLOAD_ORIGINAL_SIZE_BYTES, 524288L),
            stringArrayAttr(GenAiAttributes.RESPONSE_FINISH_REASONS, listOf("stop", "length")),
        )
}
