package io.github.unityinflow.agentsemconv

/**
 * Typed values for the GenAI operation-name attribute (see
 * [GenAiAttributes.OPERATION_NAME]) relevant to agent-tracer node
 * classification; [fromOtel] parses an OTLP string into a variant, returning
 * `null` for unknown operations (absence is information — never throws).
 */
enum class GenAiOperation {
    /** `invoke_agent` — an agent run or subagent spawn. */
    INVOKE_AGENT,

    /** `execute_tool` — a tool-execution node. */
    EXECUTE_TOOL,

    /** `chat` — a chat-style LLM call. */
    CHAT,

    /** `generate_content` — a content-generation LLM call. */
    GENERATE_CONTENT,

    /** `text_completion` — a text-completion LLM call. */
    TEXT_COMPLETION,

    /** `plan` — a plan-step node. */
    PLAN,

    /** `create_agent` — agent setup (not a run step). */
    CREATE_AGENT,
    ;

    companion object {
        /**
         * Parses an OTLP operation-name string into a [GenAiOperation].
         *
         * Returns `null` for any value not modelled here (unknown or
         * not-relevant-for-v1.0 operations) — callers treat absence as
         * information rather than an error (D-12). Never throws.
         */
        fun fromOtel(value: String): GenAiOperation? =
            when (value) {
                "invoke_agent" -> INVOKE_AGENT
                "execute_tool" -> EXECUTE_TOOL
                "chat" -> CHAT
                "generate_content" -> GENERATE_CONTENT
                "text_completion" -> TEXT_COMPLETION
                "plan" -> PLAN
                "create_agent" -> CREATE_AGENT
                else -> null
            }
    }
}
