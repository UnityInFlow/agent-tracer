package io.github.unityinflow.agenttracer.query.dto

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A page of run summaries (API-03).
 *
 * @property runs the run summaries for this page, newest-first.
 * @property limit the effective page size applied (clamped ≤ 500 by the mapper).
 * @property offset the effective page offset applied (floored at 0 by the mapper).
 */
@Serializable
data class RunListResponse(
    val runs: List<RunSummary>,
    val limit: Int,
    val offset: Int,
)

/**
 * A single run's list-scale summary, projected from `storage.AgentRunRow`.
 *
 * @property outcome the **list-scale outcome proxy** — this is the `AgentRunRow.outcome` value, which
 *   the `agent_runs` rollup derives via `anyLast(Outcome)` over the run's spans (RESEARCH A3,
 *   user-confirmed). It is an approximation suitable for the run list. The **authoritative** per-run
 *   outcome (the true root-span status) is exposed separately on the `/api/runs/{id}/dag` endpoint
 *   (Plan 04), where the root span's `status` is available. Do not treat this list-level value as the
 *   ground-truth run status; use the DAG endpoint when correctness matters.
 */
@Serializable
data class RunSummary(
    val traceId: String,
    val agentName: String,
    val model: String,
    val outcome: String,
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    @Serializable(with = InstantSerializer::class)
    val endTime: Instant,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val maxSaturation: Float,
    val spanCount: Long,
)
