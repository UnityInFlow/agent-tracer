package io.github.unityinflow.agenttracer.demo

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agentsemconv.UnityInFlowAttributes
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlin.random.Random

/**
 * Generates realistic, full-convention agent-run span trees driven entirely by a
 * seeded [Random], then emits them through an OTel [Tracer].
 *
 * **Determinism (SC4):** every random decision — skill count, subagent depth,
 * token counts, cost, saturation curve, outcome — is drawn from [rng] while
 * building an immutable [RunScenario], so the same seed reproduces an identical
 * scenario and therefore an identical span tree (same names, nesting, and
 * attribute maps). The emit step is a pure walk of the scenario.
 *
 * **Full convention (SC4):** each run is an `invoke_agent` root with
 * `gen_ai.agent.name` + request/response model and a child `plan`; under it
 * ≥[MIN_SKILLS] skill spans (`unityinflow.skill.name`), ≥2 nested-subagent levels
 * (each a nested `invoke_agent` via `setParent`), and `execute_tool` spans
 * (`gen_ai.tool.name`). Every span sets `unityinflow.cost.usd` + token counts; a
 * rising `unityinflow.context.saturation_pct` (0–100) is sampled across the run
 * — without it the SC3 saturation tests prove nothing (Pitfall 6). This emitter
 * is the SOLE producer of saturation data.
 *
 * **Keys (CONV-01):** all attribute KEYS come from agent-semconv constants
 * ([GenAiAttributes] / [UnityInFlowAttributes]) — never a hardcoded literal here.
 *
 * @property rng the seeded random source; pass `Random(seed)` for reproducibility.
 */
class SeededRunFactory(
    private val rng: Random,
) {
    /**
     * Builds one seeded [RunScenario] and emits its full span tree through
     * [tracer], nesting children under their parent's [Context] and ending spans
     * in post-order (children before parents).
     */
    fun generateRun(tracer: Tracer) {
        val scenario = nextScenario()
        emitNode(tracer, scenario.root, Context.current(), scenario)
    }

    /**
     * Resolves a complete [RunScenario] from [rng]. Saturation is threaded through
     * a single rising accumulator so its samples climb monotonically across the
     * run timeline (depth-first emit order).
     */
    private fun nextScenario(): RunScenario {
        val saturation = RisingSaturation(rng)
        val agentName = AGENT_NAMES[rng.nextInt(AGENT_NAMES.size)]
        val requestModel = MODELS[rng.nextInt(MODELS.size)]
        val outcome = OUTCOMES[rng.nextInt(OUTCOMES.size)]

        // Root invoke_agent → plan → (skills + nested subagents + tools).
        val planChildren =
            buildList {
                val skillCount = MIN_SKILLS + rng.nextInt(MAX_EXTRA_SKILLS + 1)
                repeat(skillCount) { add(nextSkill(saturation, depth = 1)) }
                // ≥2 nested-subagent levels under the plan (each a nested invoke_agent).
                add(nextSubagent(saturation, remainingLevels = MIN_SUBAGENT_LEVELS, depth = 1))
            }
        val plan =
            ScenarioNode.Plan(
                inputTokens = nextTokens(),
                outputTokens = nextTokens(),
                costUsd = nextCost(),
                saturationPct = saturation.next(),
                planStep = "step-${rng.nextInt(1, 6)}",
                children = planChildren,
            )
        val root =
            ScenarioNode.Agent(
                inputTokens = nextTokens(),
                outputTokens = nextTokens(),
                costUsd = nextCost(),
                saturationPct = saturation.next(),
                agentName = agentName,
                children = listOf(plan),
            )
        return RunScenario(
            agentName = agentName,
            requestModel = requestModel,
            responseModel = requestModel,
            outcome = outcome,
            root = root,
        )
    }

    private fun nextSkill(
        saturation: RisingSaturation,
        depth: Int,
    ): ScenarioNode.Skill {
        val toolCount = rng.nextInt(MAX_TOOLS_PER_SKILL + 1)
        val tools = (0 until toolCount).map { nextTool(saturation) }
        return ScenarioNode.Skill(
            inputTokens = nextTokens(),
            outputTokens = nextTokens(),
            costUsd = nextCost(),
            // Sample saturation on roughly every other skill so the series is sparse-but-rising.
            saturationPct = if (rng.nextBoolean()) saturation.next() else null,
            skillName = SKILLS[rng.nextInt(SKILLS.size)] + "@$depth",
            children = tools,
        )
    }

    /**
     * Builds a nested `invoke_agent` subagent. [remainingLevels] guarantees the
     * minimum nesting depth so ≥2 nested invoke_agent levels always exist;
     * additional levels may be added randomly.
     */
    private fun nextSubagent(
        saturation: RisingSaturation,
        remainingLevels: Int,
        depth: Int,
    ): ScenarioNode.Agent {
        val children =
            buildList {
                // One skill per subagent level so the convention surface is exercised at depth.
                add(nextSkill(saturation, depth = depth + 1))
                val deeper = remainingLevels > 1 || (remainingLevels == 1 && rng.nextBoolean())
                if (deeper) {
                    add(nextSubagent(saturation, remainingLevels = remainingLevels - 1, depth = depth + 1))
                } else {
                    add(nextTool(saturation))
                }
            }
        return ScenarioNode.Agent(
            inputTokens = nextTokens(),
            outputTokens = nextTokens(),
            costUsd = nextCost(),
            saturationPct = saturation.next(),
            agentName = "${SUBAGENTS[rng.nextInt(SUBAGENTS.size)]}-L$depth",
            children = children,
        )
    }

    private fun nextTool(saturation: RisingSaturation): ScenarioNode.Tool =
        ScenarioNode.Tool(
            inputTokens = nextTokens(),
            outputTokens = nextTokens(),
            costUsd = nextCost(),
            saturationPct = if (rng.nextBoolean()) saturation.next() else null,
            toolName = TOOLS[rng.nextInt(TOOLS.size)],
        )

    private fun nextTokens(): Long = (rng.nextInt(50, 4000)).toLong()

    private fun nextCost(): Double = (rng.nextInt(1, 5000)) / 1000.0

    /**
     * Emits [node] as an OTel span parented at [parent], recurses into children
     * under THIS span's context, and ends the span in post-order. Common
     * convention attributes (operation, tokens, cost, saturation, schema version)
     * are set on every span; node-specific keys are added per variant.
     */
    private fun emitNode(
        tracer: Tracer,
        node: ScenarioNode,
        parent: Context,
        scenario: RunScenario,
    ) {
        val span =
            tracer
                .spanBuilder(node.operation)
                .setParent(parent)
                .setAttribute(opNameKey, node.operation)
                .setAttribute(inputTokensKey, node.inputTokens)
                .setAttribute(outputTokensKey, node.outputTokens)
                .setAttribute(costKey, node.costUsd)
                .setAttribute(schemaVersionKey, SCHEMA_VERSION)
                .startSpan()

        node.saturationPct?.let { span.setAttribute(saturationKey, it) }

        when (node) {
            is ScenarioNode.Agent -> {
                span.setAttribute(agentNameKey, node.agentName)
                span.setAttribute(requestModelKey, scenario.requestModel)
                span.setAttribute(responseModelKey, scenario.responseModel)
                span.setAttribute(outcomeKey, scenario.outcome)
            }

            is ScenarioNode.Plan -> span.setAttribute(planStepKey, node.planStep)
            is ScenarioNode.Skill -> span.setAttribute(skillNameKey, node.skillName)
            is ScenarioNode.Tool -> span.setAttribute(toolNameKey, node.toolName)
        }

        val childContext = parent.with(span)
        childrenOf(node).forEach { child -> emitNode(tracer, child, childContext, scenario) }
        span.end()
    }

    private fun childrenOf(node: ScenarioNode): List<ScenarioNode> =
        when (node) {
            is ScenarioNode.Agent -> node.children
            is ScenarioNode.Plan -> node.children
            is ScenarioNode.Skill -> node.children
            is ScenarioNode.Tool -> emptyList()
        }

    /**
     * A monotonically-rising context-saturation series in [0, 100], seeded from
     * [rng]. Each [next] adds a small random increment, clamped at 100.0.
     */
    private class RisingSaturation(
        private val rng: Random,
    ) {
        // Sanctioned `var`: a monotonic accumulator is inherently stateful; the
        // mutation is isolated to this tiny private class and never escapes.
        private var current: Double = rng.nextInt(0, 15).toDouble()

        fun next(): Double {
            current = (current + rng.nextInt(3, 18)).coerceAtMost(100.0)
            return current
        }
    }

    private companion object {
        const val MIN_SKILLS = 3
        const val MAX_EXTRA_SKILLS = 2
        const val MAX_TOOLS_PER_SKILL = 2
        const val MIN_SUBAGENT_LEVELS = 2
        const val SCHEMA_VERSION = "1.0"

        val AGENT_NAMES = listOf("planner-agent", "orchestrator", "researcher", "coder-agent")
        val SUBAGENTS = listOf("subagent", "worker", "helper", "spawned")
        val MODELS = listOf("claude-opus-4", "claude-sonnet-4", "gpt-5", "gemini-2.5-pro")
        val SKILLS = listOf("code-review", "tdd", "debugging", "planning", "brainstorming")
        val TOOLS = listOf("Read", "Write", "Bash", "Grep", "Edit")
        val OUTCOMES = listOf("success", "failure", "partial")

        // Keys sourced exclusively from agent-semconv constants (CONV-01).
        val opNameKey = AttributeKey.stringKey(GenAiAttributes.OPERATION_NAME)
        val agentNameKey = AttributeKey.stringKey(GenAiAttributes.AGENT_NAME)
        val requestModelKey = AttributeKey.stringKey(GenAiAttributes.REQUEST_MODEL)
        val responseModelKey = AttributeKey.stringKey(GenAiAttributes.RESPONSE_MODEL)
        val toolNameKey = AttributeKey.stringKey(GenAiAttributes.TOOL_NAME)
        val inputTokensKey = AttributeKey.longKey(GenAiAttributes.USAGE_INPUT_TOKENS)
        val outputTokensKey = AttributeKey.longKey(GenAiAttributes.USAGE_OUTPUT_TOKENS)

        val skillNameKey = AttributeKey.stringKey(UnityInFlowAttributes.SKILL_NAME)
        val saturationKey = AttributeKey.doubleKey(UnityInFlowAttributes.CONTEXT_SATURATION_PCT)
        val costKey = AttributeKey.doubleKey(UnityInFlowAttributes.COST_USD)
        val planStepKey = AttributeKey.stringKey(UnityInFlowAttributes.PLAN_STEP)
        val outcomeKey = AttributeKey.stringKey(UnityInFlowAttributes.OUTCOME)
        val schemaVersionKey = AttributeKey.stringKey(UnityInFlowAttributes.SCHEMA_VERSION)
    }
}
