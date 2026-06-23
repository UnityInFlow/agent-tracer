package io.github.unityinflow.agenttracer.query

import kotlinx.serialization.Serializable

/**
 * Flat, query-time DAG of a single agent run (API-01/API-02).
 *
 * The graph is materialized as a `nodes[] + edges[]` adjacency rather than a nested tree: it is
 * tolerant of late, out-of-order, and orphaned spans (an orphan is reparented under a synthetic
 * root, never dropped), and the flat shape lets the frontend's elkjs layout engine compute compound
 * positions without the backend committing to a tree topology. Immutable `@Serializable` (no `var`).
 */
@Serializable
data class DagResponse(
    val nodes: List<DagNode>,
    val edges: List<DagEdge>,
)

/**
 * One node in the [DagResponse] — a span (or the appended synthetic root).
 *
 * @property id the `SpanRow.spanId` (or [DagAssembler.SYNTHETIC_ROOT_ID] for the synthetic root).
 * @property parentId the parent node id; `null` for the true run root, the synthetic-root id for an
 *   orphaned span (SC1).
 * @property kind the GenAI operation name (`SpanRow.operation`), or `"unknown"` for the `""` sentinel.
 * @property costUsd this node's own cost; `null` when unmeasured (emitted as JSON null).
 * @property subtreeCostUsd this node's own cost + every descendant's cost, cycle-guarded roll-up (SC2).
 * @property group whether this node is an agent-invocation group (`operation == "INVOKE_AGENT"`).
 * @property orphaned whether this span's named parent was absent from the trace (reparented, SC1).
 * @property isSyntheticRoot whether this is the appended synthetic root holding the orphan subtree.
 */
@Serializable
data class DagNode(
    val id: String,
    val parentId: String?,
    val kind: String,
    val costUsd: Double?,
    val subtreeCostUsd: Double,
    val tokens: Long,
    val group: Boolean,
    val orphaned: Boolean,
    val isSyntheticRoot: Boolean,
    val startTimeUnixNanos: Long,
    val durationNanos: Long,
    val status: String,
    val agentName: String?,
    val skillName: String?,
)

/** A directed `parent → child` edge in the [DagResponse]. */
@Serializable
data class DagEdge(
    val from: String,
    val to: String,
)
