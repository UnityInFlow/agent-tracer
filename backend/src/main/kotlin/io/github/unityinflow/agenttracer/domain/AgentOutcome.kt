package io.github.unityinflow.agenttracer.domain

import io.github.unityinflow.agentsemconv.UnityInFlowAttributes

/**
 * Terminal outcome of an agent span/run (D-09).
 *
 * Defaults to the span's OTel status (OK → [Success], ERROR → [Failure]); an
 * optional `unityinflow.outcome` string overrides when the emitter knows better.
 */
enum class AgentOutcome {
    /** The span/run completed successfully. */
    SUCCESS,

    /** The span/run failed. */
    FAILURE,

    /** The span/run partially succeeded. */
    PARTIAL,

    /** The span/run was cancelled. */
    CANCELLED,
    ;

    companion object {
        /**
         * Derives an [AgentOutcome] from the OTel span status, with an optional
         * `unityinflow.outcome` override (D-09).
         *
         * @param otelStatusIsError `true` when the OTel span status code is ERROR;
         *   `false` for OK/UNSET — maps to [FAILURE] / [SUCCESS] respectively.
         * @param outcomeOverride the verbatim `unityinflow.outcome` value (see
         *   [UnityInFlowAttributes.OUTCOME]); when it names a known outcome it
         *   wins over the OTel status. Unknown/absent overrides fall through to
         *   the status-derived value. Never throws.
         */
        fun derive(
            otelStatusIsError: Boolean,
            outcomeOverride: String?,
        ): AgentOutcome {
            val overridden = outcomeOverride?.let { fromOtel(it) }
            if (overridden != null) return overridden
            return if (otelStatusIsError) FAILURE else SUCCESS
        }

        /**
         * Parses a `unityinflow.outcome` string into an [AgentOutcome], returning
         * `null` for any unknown value (absence is information — never throws, D-12).
         */
        fun fromOtel(value: String): AgentOutcome? =
            when (value) {
                "success" -> SUCCESS
                "failure" -> FAILURE
                "partial" -> PARTIAL
                "cancelled" -> CANCELLED
                else -> null
            }
    }
}
