package io.github.unityinflow.agentsemconv

/**
 * Vendored `unityinflow.*` attribute keys — agent-tracer's own extensions to
 * the OpenTelemetry GenAI semantic convention.
 *
 * This object is the ONLY place raw `unityinflow.*` key literals may appear
 * (CONV-01). These keys cover agent-native concepts the GenAI semconv does not
 * model: skill activation, context saturation, cost, plan steps, outcome, and
 * payload-truncation bookkeeping.
 */
object UnityInFlowAttributes {
    /** `unityinflow.skill.name` — the activated skill's name. */
    const val SKILL_NAME = "unityinflow.skill.name"

    /** `unityinflow.skill.version` — the activated skill's version. */
    const val SKILL_VERSION = "unityinflow.skill.version"

    /** `unityinflow.context.saturation_pct` — context-window saturation, 0–100 (double). */
    const val CONTEXT_SATURATION_PCT = "unityinflow.context.saturation_pct"

    /** `unityinflow.cost.usd` — monetary cost of the operation in USD (double). */
    const val COST_USD = "unityinflow.cost.usd"

    /** `unityinflow.plan.step` — the plan-step identifier this span belongs to. */
    const val PLAN_STEP = "unityinflow.plan.step"

    /** `unityinflow.outcome` — terminal outcome (`success`/`failure`/`partial`/`cancelled`). */
    const val OUTCOME = "unityinflow.outcome"

    /** `unityinflow.schema_version` — semver of the agent-span convention. */
    const val SCHEMA_VERSION = "unityinflow.schema_version"

    /** `unityinflow.payload.truncated` — whether a payload was truncated (bool). */
    const val PAYLOAD_TRUNCATED = "unityinflow.payload.truncated"

    /** `unityinflow.payload.original_size_bytes` — pre-truncation payload size (int64). */
    const val PAYLOAD_ORIGINAL_SIZE_BYTES = "unityinflow.payload.original_size_bytes"
}
