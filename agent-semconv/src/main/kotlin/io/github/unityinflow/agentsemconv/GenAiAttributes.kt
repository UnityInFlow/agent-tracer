package io.github.unityinflow.agentsemconv

/**
 * Vendored OpenTelemetry GenAI semantic-convention attribute keys.
 *
 * This object is the ONLY place raw `gen_ai.*` key literals may appear. The
 * GenAI semconv is Development-stability and keys are renamed across releases;
 * isolating every literal here makes a future rename a one-file change
 * (CONV-01). Downstream emitters (KORE) and the extractor reference these
 * constants instead of hardcoding strings.
 */
object GenAiAttributes {
    /** `gen_ai.operation.name` — the operation kind (string enum; see [GenAiOperation]). */
    const val OPERATION_NAME = "gen_ai.operation.name"

    /** `gen_ai.provider.name` — the GenAI provider (e.g. `anthropic`, `openai`). */
    const val PROVIDER_NAME = "gen_ai.provider.name"

    /** `gen_ai.request.model` — the model requested by the caller. */
    const val REQUEST_MODEL = "gen_ai.request.model"

    /** `gen_ai.response.model` — the model that actually served the response. */
    const val RESPONSE_MODEL = "gen_ai.response.model"

    /** `gen_ai.usage.input_tokens` — prompt tokens consumed (int64). */
    const val USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens"

    /** `gen_ai.usage.output_tokens` — completion tokens produced (int64). */
    const val USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens"

    /** `gen_ai.agent.name` — human-readable agent name. */
    const val AGENT_NAME = "gen_ai.agent.name"

    /** `gen_ai.agent.id` — stable agent identifier. */
    const val AGENT_ID = "gen_ai.agent.id"

    /** `gen_ai.tool.name` — the tool being executed. */
    const val TOOL_NAME = "gen_ai.tool.name"

    /** `gen_ai.tool.call.id` — identifier of a specific tool invocation. */
    const val TOOL_CALL_ID = "gen_ai.tool.call.id"

    /** `gen_ai.conversation.id` — groups related spans into a conversation. */
    const val CONVERSATION_ID = "gen_ai.conversation.id"

    /** `gen_ai.response.finish_reasons` — finish reasons (string list). */
    const val RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons"

    /** `gen_ai.input.messages` — prompt payload (opt-in; carried verbatim). */
    const val INPUT_MESSAGES = "gen_ai.input.messages"

    /** `gen_ai.output.messages` — completion payload (opt-in; carried verbatim). */
    const val OUTPUT_MESSAGES = "gen_ai.output.messages"

    /** `gen_ai.system_instructions` — system-prompt payload (opt-in). */
    const val SYSTEM_INSTRUCTIONS = "gen_ai.system_instructions"

    /**
     * `service.name` — OTel resource attribute. Not a `gen_ai.*` key, but
     * vendored here as the agent-identity fallback (D-10) used by the extractor.
     */
    const val SERVICE_NAME = "service.name"
}
