package io.github.unityinflow.agenttracer.demo

/**
 * Immutable, fully-resolved shape of one synthetic agent run.
 *
 * [SeededRunFactory] derives a `RunScenario` from a seeded [kotlin.random.Random]
 * BEFORE touching the OTel `Tracer`, so the entire random sequence (skill counts,
 * subagent depth, token counts, cost, saturation curve, outcome) is fixed for a
 * given seed — the span tree is then a pure function of this scenario, making the
 * same seed reproduce an identical export (SC4).
 *
 * The node hierarchy mirrors the agent-span convention:
 * `invoke_agent` (root) → `plan` → skills + nested `invoke_agent` subagents → tools.
 *
 * @property agentName the root agent's `gen_ai.agent.name`.
 * @property requestModel the `gen_ai.request.model`.
 * @property responseModel the `gen_ai.response.model`.
 * @property outcome the run's terminal `unityinflow.outcome` (mixed across runs).
 * @property root the root `invoke_agent` node.
 */
data class RunScenario(
    val agentName: String,
    val requestModel: String,
    val responseModel: String,
    val outcome: String,
    val root: ScenarioNode.Agent,
)

/**
 * One node in a [RunScenario] tree. Sealed so the factory walks it exhaustively
 * (the repo's sealed-class domain idiom). Each node carries the resolved
 * attributes it will set on its OTel span.
 *
 * @property operation the lowercase OTLP operation value (span name + the
 *   `gen_ai.operation.name` attribute) parsed by `GenAiOperation.fromOtel`.
 * @property inputTokens `gen_ai.usage.input_tokens`.
 * @property outputTokens `gen_ai.usage.output_tokens`.
 * @property costUsd `unityinflow.cost.usd`.
 * @property saturationPct `unityinflow.context.saturation_pct` (0–100), or `null`
 *   when this node does not sample the rising saturation series.
 */
sealed interface ScenarioNode {
    val operation: String
    val inputTokens: Long
    val outputTokens: Long
    val costUsd: Double
    val saturationPct: Double?

    /** An `invoke_agent` node (the root run or a nested subagent spawn). */
    data class Agent(
        override val inputTokens: Long,
        override val outputTokens: Long,
        override val costUsd: Double,
        override val saturationPct: Double?,
        val agentName: String,
        val children: List<ScenarioNode>,
    ) : ScenarioNode {
        override val operation: String get() = OP_INVOKE_AGENT
    }

    /** A `plan` node grouping the run's plan steps. */
    data class Plan(
        override val inputTokens: Long,
        override val outputTokens: Long,
        override val costUsd: Double,
        override val saturationPct: Double?,
        val planStep: String,
        val children: List<ScenarioNode>,
    ) : ScenarioNode {
        override val operation: String get() = OP_PLAN
    }

    /** A skill-activation node carrying `unityinflow.skill.name`. */
    data class Skill(
        override val inputTokens: Long,
        override val outputTokens: Long,
        override val costUsd: Double,
        override val saturationPct: Double?,
        val skillName: String,
        val children: List<ScenarioNode>,
    ) : ScenarioNode {
        // A skill activation is modelled as a `chat` LLM call carrying the skill name.
        override val operation: String get() = OP_CHAT
    }

    /** An `execute_tool` node carrying `gen_ai.tool.name`. */
    data class Tool(
        override val inputTokens: Long,
        override val outputTokens: Long,
        override val costUsd: Double,
        override val saturationPct: Double?,
        val toolName: String,
    ) : ScenarioNode {
        override val operation: String get() = OP_EXECUTE_TOOL
    }

    companion object {
        // Lowercase OTLP operation values — matching what GenAiOperation.fromOtel parses.
        const val OP_INVOKE_AGENT = "invoke_agent"
        const val OP_PLAN = "plan"
        const val OP_CHAT = "chat"
        const val OP_EXECUTE_TOOL = "execute_tool"
    }
}
