package io.github.unityinflow.agenttracer.convention

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agentsemconv.GenAiOperation
import io.github.unityinflow.agentsemconv.UnityInFlowAttributes
import io.github.unityinflow.agenttracer.domain.AgentOutcome
import io.github.unityinflow.agenttracer.domain.AgentSpan

/**
 * Pure, no-I/O transformer from a parsed OTLP span's attributes into a typed
 * [AgentSpan].
 *
 * For each logical field the lookup is priority-ordered: read the
 * [UnityInFlowAttributes] key first, then the standard [GenAiAttributes] key,
 * then a resource fallback, else
 * `null` (D-11 precedence). OTLP value types are preserved via [AttrView]; the
 * extractor NEVER throws on malformed/missing/mistyped input — it relies on
 * [AttrView] returning `null` (Security V5 / DoS, T-01-03). All key strings come
 * from the vendored `agent-semconv` constants — no raw literals here (CONV-01).
 */
object AgentSpanExtractor {
    /**
     * Extracts an [AgentSpan] from span [attrs] and [resource] attributes.
     *
     * @param attrs the span's own attributes.
     * @param resource the resource attributes (carries [GenAiAttributes.SERVICE_NAME]
     *   for the D-10 agent-identity fallback).
     * @param otelStatusIsError `true` when the OTel span status code is ERROR
     *   (drives the default [AgentOutcome] before any [UnityInFlowAttributes.OUTCOME]
     *   override, D-09). Defaults to `false` (OK/UNSET → success).
     */
    fun extract(
        attrs: AttrView,
        resource: AttrView,
        otelStatusIsError: Boolean = false,
    ): AgentSpan =
        AgentSpan(
            agentName =
                attrs.string(GenAiAttributes.AGENT_NAME)
                    ?: resource.string(GenAiAttributes.SERVICE_NAME),
            model =
                attrs.string(GenAiAttributes.RESPONSE_MODEL)
                    ?: attrs.string(GenAiAttributes.REQUEST_MODEL),
            skillName =
                attrs.string(UnityInFlowAttributes.SKILL_NAME)
                    ?: attrs.string(GenAiAttributes.TOOL_NAME),
            skillVersion = attrs.string(UnityInFlowAttributes.SKILL_VERSION),
            inputTokens = attrs.long(GenAiAttributes.USAGE_INPUT_TOKENS),
            outputTokens = attrs.long(GenAiAttributes.USAGE_OUTPUT_TOKENS),
            costUsd = attrs.double(UnityInFlowAttributes.COST_USD),
            saturationPct =
                attrs
                    .double(UnityInFlowAttributes.CONTEXT_SATURATION_PCT)
                    ?.coerceIn(0.0, 100.0),
            planStep = attrs.int(UnityInFlowAttributes.PLAN_STEP),
            operation =
                attrs
                    .string(GenAiAttributes.OPERATION_NAME)
                    ?.let { GenAiOperation.fromOtel(it) },
            outcome =
                AgentOutcome.derive(
                    otelStatusIsError = otelStatusIsError,
                    outcomeOverride = attrs.string(UnityInFlowAttributes.OUTCOME),
                ),
            inputPayload = attrs.string(GenAiAttributes.INPUT_MESSAGES),
            outputPayload = attrs.string(GenAiAttributes.OUTPUT_MESSAGES),
            systemInstructions = attrs.string(GenAiAttributes.SYSTEM_INSTRUCTIONS),
            payloadTruncated = attrs.bool(UnityInFlowAttributes.PAYLOAD_TRUNCATED),
            payloadOriginalSizeBytes = attrs.long(UnityInFlowAttributes.PAYLOAD_ORIGINAL_SIZE_BYTES),
            finishReasons = attrs.stringList(GenAiAttributes.RESPONSE_FINISH_REASONS),
            schemaVersion = attrs.string(UnityInFlowAttributes.SCHEMA_VERSION),
        )
}
