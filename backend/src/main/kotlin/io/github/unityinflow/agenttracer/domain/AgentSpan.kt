package io.github.unityinflow.agenttracer.domain

import io.github.unityinflow.agentsemconv.GenAiOperation

/**
 * Immutable, typed view of a single agent span extracted from OTLP attributes.
 *
 * Every field is nullable (except [outcome]): absence is information, so missing
 * attributes are `null` rather than sentinel defaults (D-12). Token counts are
 * `Long?` to preserve OTLP `int_value` (int64) — never stringified (Pitfall 1).
 * Payload fields are carried verbatim and never parsed (D-14).
 */
data class AgentSpan(
    /** Agent identity: `gen_ai.agent.name`, falling back to resource `service.name` (D-10). */
    val agentName: String?,
    /** Model that served the response (`gen_ai.response.model`), else the requested model. */
    val model: String?,
    /** Activated skill name: `unityinflow.skill.name`, else `gen_ai.tool.name` (D-11). */
    val skillName: String?,
    /** Activated skill version (`unityinflow.skill.version`, D-07). */
    val skillVersion: String?,
    /** Prompt tokens consumed (`gen_ai.usage.input_tokens`, D-06). */
    val inputTokens: Long?,
    /** Completion tokens produced (`gen_ai.usage.output_tokens`, D-06). */
    val outputTokens: Long?,
    /** Operation cost in USD (`unityinflow.cost.usd`, D-05). */
    val costUsd: Double?,
    /** Context-window saturation, clamped to [0,100] (`unityinflow.context.saturation_pct`, D-04). */
    val saturationPct: Double?,
    /** Ordinal plan step (`unityinflow.plan.step`, D-07). */
    val planStep: Int?,
    /** Operation classification from `gen_ai.operation.name`; `null` when unknown. */
    val operation: GenAiOperation?,
    /** Terminal outcome derived from OTel status, overridable by `unityinflow.outcome` (D-09). */
    val outcome: AgentOutcome,
    /** Prompt payload (`gen_ai.input.messages`), carried verbatim — never parsed (D-14). */
    val inputPayload: String?,
    /** Completion payload (`gen_ai.output.messages`), carried verbatim — never parsed (D-14). */
    val outputPayload: String?,
    /** System-prompt payload (`gen_ai.system_instructions`), carried verbatim. */
    val systemInstructions: String?,
    /** Whether a payload was truncated (`unityinflow.payload.truncated`, D-13). */
    val payloadTruncated: Boolean?,
    /** Pre-truncation payload size in bytes (`unityinflow.payload.original_size_bytes`, D-13). */
    val payloadOriginalSizeBytes: Long?,
    /** Response finish reasons (`gen_ai.response.finish_reasons`). */
    val finishReasons: List<String>?,
    /** Convention schema version stamped by the emitter (`unityinflow.schema_version`, D-02). */
    val schemaVersion: String?,
)
