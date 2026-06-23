package io.github.unityinflow.agenttracer.query

import io.github.unityinflow.agenttracer.storage.SpanRow

/**
 * Pure, query-time assembler that turns a flat `List<SpanRow>` into a flat [DagResponse]
 * (`nodes[] + edges[]`) for the agent DAG view (API-01/API-02).
 *
 * No SQL, no coroutines, no repository — storage remains the sole SQL holder. The transform is a
 * single set operation over the full span list, so it is tolerant of late, out-of-order, and
 * orphaned arrivals:
 *
 *  - **Orphan tolerance (SC1):** a span whose `parentSpanId` is non-empty but names a span absent
 *    from the set is reparented under the synthetic root ([SYNTHETIC_ROOT_ID], `orphaned = true`) —
 *    never dropped. The synthetic root node is appended only when at least one orphan exists.
 *  - **True root:** a span with `parentSpanId == ""` maps to `parentId = null`.
 *  - **Order-independence:** placement is by `parentSpanId` membership (an `associateBy` map), never
 *    by arrival order; the synthetic root is never assumed to be the first span.
 *  - **Subtree cost roll-up (SC2):** each node's `subtreeCostUsd` is its own `costUsd` (null → 0)
 *    plus every descendant's cost, via a cycle-guarded post-order walk over the children adjacency,
 *    so a malformed/self-referential graph cannot recurse unbounded.
 */
class DagAssembler {
    fun assemble(spans: List<SpanRow>): DagResponse {
        if (spans.isEmpty()) return DagResponse(nodes = emptyList(), edges = emptyList())

        val byId: Map<String, SpanRow> = spans.associateBy { it.spanId }

        // Resolve each span's effective parent id (null = true root, SYNTHETIC_ROOT_ID = orphan).
        val resolvedParent: Map<String, String?> =
            spans.associate { span ->
                val parent = span.parentSpanId
                val resolved: String? =
                    when {
                        parent.isEmpty() -> null
                        parent in byId -> parent
                        else -> SYNTHETIC_ROOT_ID
                    }
                span.spanId to resolved
            }
        val hasOrphan: Boolean = resolvedParent.values.any { it == SYNTHETIC_ROOT_ID }

        // Edges: one parent → child edge per node with a non-null parent (incl. synthetic-root edges).
        val edges: List<DagEdge> =
            spans.mapNotNull { span ->
                resolvedParent[span.spanId]?.let { parentId -> DagEdge(from = parentId, to = span.spanId) }
            }

        val subtreeCosts: Map<String, Double> = rollUpCosts(spans, resolvedParent, hasOrphan)

        val spanNodes: List<DagNode> =
            spans.map { span ->
                DagNode(
                    id = span.spanId,
                    parentId = resolvedParent[span.spanId],
                    kind = span.operation.ifEmpty { "unknown" },
                    costUsd = span.costUsd,
                    subtreeCostUsd = subtreeCosts[span.spanId] ?: (span.costUsd ?: 0.0),
                    tokens = (span.inputTokens ?: 0L) + (span.outputTokens ?: 0L),
                    group = span.operation.equals("INVOKE_AGENT", ignoreCase = true),
                    orphaned = resolvedParent[span.spanId] == SYNTHETIC_ROOT_ID,
                    isSyntheticRoot = false,
                    startTimeUnixNanos = span.timestamp.toEpochMilli() * 1_000_000,
                    durationNanos = span.duration,
                    status = span.statusCode,
                    agentName = span.agentName.ifEmpty { null },
                    skillName = span.skillName.ifEmpty { null },
                )
            }

        val nodes: List<DagNode> =
            if (hasOrphan) {
                spanNodes +
                    DagNode(
                        id = SYNTHETIC_ROOT_ID,
                        parentId = null,
                        kind = "synthetic_root",
                        costUsd = null,
                        subtreeCostUsd = subtreeCosts[SYNTHETIC_ROOT_ID] ?: 0.0,
                        tokens = 0L,
                        group = true,
                        orphaned = false,
                        isSyntheticRoot = true,
                        startTimeUnixNanos = 0L,
                        durationNanos = 0L,
                        status = "",
                        agentName = null,
                        skillName = null,
                    )
            } else {
                spanNodes
            }

        return DagResponse(nodes = nodes, edges = edges)
    }

    /**
     * Cycle-guarded post-order roll-up: each id maps to (own cost) + (sum of descendants' costs).
     * Own cost of the synthetic root is 0; orphan subtree costs therefore accumulate into it.
     */
    private fun rollUpCosts(
        spans: List<SpanRow>,
        resolvedParent: Map<String, String?>,
        hasOrphan: Boolean,
    ): Map<String, Double> {
        val ownCost: MutableMap<String, Double> =
            spans.associate { it.spanId to (it.costUsd ?: 0.0) }.toMutableMap()
        if (hasOrphan) ownCost[SYNTHETIC_ROOT_ID] = 0.0

        // children adjacency: parentId → list of child ids.
        val children: MutableMap<String, MutableList<String>> = mutableMapOf()
        resolvedParent.forEach { (childId, parentId) ->
            if (parentId != null) children.getOrPut(parentId) { mutableListOf() }.add(childId)
        }

        val memo: MutableMap<String, Double> = mutableMapOf()
        val onPath: MutableSet<String> = mutableSetOf()

        fun subtree(id: String): Double {
            memo[id]?.let { return it }
            if (id in onPath) return ownCost[id] ?: 0.0 // cycle guard: count own cost once, stop.
            onPath.add(id)
            val total = (ownCost[id] ?: 0.0) + (children[id]?.sumOf { child -> subtree(child) } ?: 0.0)
            onPath.remove(id)
            memo[id] = total
            return total
        }

        ownCost.keys.forEach { subtree(it) }
        return memo
    }

    companion object {
        /** Id of the synthetic root that holds otherwise-orphaned spans (never collides with a real span id). */
        const val SYNTHETIC_ROOT_ID = "__synthetic_root__"
    }
}
