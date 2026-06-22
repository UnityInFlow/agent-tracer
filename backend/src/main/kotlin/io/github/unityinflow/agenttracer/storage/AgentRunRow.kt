package io.github.unityinflow.agenttracer.storage

import java.time.Instant

/**
 * Read model for an `agent_runs` rollup row (004_agent_runs_mv.sql) — one per trace/run (D-08).
 *
 * Immutable `data class`: unlike [SpanRow]/[PayloadRow] this is never `register()`-ed for a POJO
 * insert (the rollup is produced server-side by the `agent_runs_mv` materialized view); it is only
 * mapped FROM a query result via the binary reader, so no JavaBean setter / no-arg constructor is
 * required and the CLAUDE.md immutable-`val` preference holds.
 *
 * The `SimpleAggregateFunction` columns read as their finalized values (no `-Merge` needed):
 * summed [inputTokens]/[outputTokens]/[costUsd]/[spanCount], `max` [maxSaturation], `min`/`max`
 * [startTime]/[endTime], and `anyLast` [agentName]/[model]/[outcome] — deterministic for the
 * complete/in-order, uniform-dimension traces that are Phase-2 scope (D-07).
 */
data class AgentRunRow(
    val traceId: String,
    val agentName: String,
    val model: String,
    val outcome: String,
    val startTime: Instant,
    val endTime: Instant,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val maxSaturation: Float,
    val spanCount: Long,
)
