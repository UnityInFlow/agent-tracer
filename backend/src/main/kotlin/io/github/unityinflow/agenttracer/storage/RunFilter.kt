package io.github.unityinflow.agenttracer.storage

import java.time.Instant

/**
 * Immutable filter for [SpanRepository.listRuns] — the Phase-4 run-list filter set, expressed
 * over the typed `agent_runs` columns (D-06).
 *
 * Every field is nullable; `null` means "unfiltered" on that dimension. The repository builds a
 * parameterized `WHERE` clause from ONLY the non-null fields, binding each value as a client-v2
 * `{name:Type}` placeholder — never string-interpolated (T-02-06 SQL-injection mitigation).
 *
 * @property agentName exact match on `agent_runs.AgentName`.
 * @property model exact match on `agent_runs.Model`.
 * @property costMin inclusive lower bound on summed `CostUsd`.
 * @property costMax inclusive upper bound on summed `CostUsd`.
 * @property outcome exact match on `agent_runs.Outcome`.
 * @property dateFrom inclusive lower bound on the run `StartTime`.
 * @property dateTo inclusive upper bound on the run `StartTime`.
 */
data class RunFilter(
    val agentName: String? = null,
    val model: String? = null,
    val costMin: Double? = null,
    val costMax: Double? = null,
    val outcome: String? = null,
    val dateFrom: Instant? = null,
    val dateTo: Instant? = null,
) {
    companion object {
        /** An unfiltered run list (every field `null`). */
        val NONE = RunFilter()
    }
}
