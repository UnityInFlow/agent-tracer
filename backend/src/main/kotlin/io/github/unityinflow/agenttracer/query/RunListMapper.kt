package io.github.unityinflow.agenttracer.query

import io.github.unityinflow.agenttracer.storage.AgentRunRow
import io.github.unityinflow.agenttracer.storage.RunFilter
import io.github.unityinflow.agenttracer.storage.SpanRepository
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Pure mapper between untrusted HTTP query-string params and the storage [RunFilter] / DTO layer
 * (API-03/D-05). This is the in-JVM validation seam (T-04-03/T-04-04): every value is coerced and
 * bounded here BEFORE it reaches the repository (the HTTP boundary itself is crossed in Plan 04).
 *
 * Validation contract (Security Domain V5):
 *  - `costMin`/`costMax`: parsed with `toDoubleOrNull()` and REJECTED if non-finite (NaN/Infinity) —
 *    a malformed value throws [IllegalArgumentException], which the route turns into 400, never a
 *    malformed [RunFilter] (T-04-03).
 *  - `dateFrom`/`dateTo`: parsed as ISO-8601 `Instant`; a bad format throws [IllegalArgumentException].
 *  - `limit`: defaults to [SpanRepository.DEFAULT_LIMIT], clamped to [MAX_LIMIT] = 500 (never
 *    unbounded), floored at 1; a non-numeric value falls back to the default (T-04-04 DoS).
 *  - `offset`: defaults to 0, floored at 0 (negative → 0); a non-numeric value falls back to 0.
 *
 * Present-only mapping: absent/blank params are left `null` (unfiltered). No SQL, no coroutines —
 * timestamp formatting for the WHERE clause stays in the repository.
 */
class RunListMapper {
    /** Maps the present query params into a [RunFilter], validating cost and date inputs. */
    fun toFilter(params: Map<String, String?>): RunFilter =
        RunFilter(
            agentName = params.nonBlank("agent"),
            model = params.nonBlank("model"),
            costMin = params.finiteDouble("costMin"),
            costMax = params.finiteDouble("costMax"),
            outcome = params.nonBlank("outcome"),
            dateFrom = params.isoInstant("dateFrom"),
            dateTo = params.isoInstant("dateTo"),
        )

    /** Effective, bounded page size: default [SpanRepository.DEFAULT_LIMIT], clamped to 1..[MAX_LIMIT]. */
    fun limitOf(params: Map<String, String?>): Int {
        val raw = params.nonBlank("limit")?.toIntOrNull() ?: SpanRepository.DEFAULT_LIMIT
        return raw.coerceIn(1, MAX_LIMIT)
    }

    /** Effective, floored page offset: default 0, negatives floored at 0. */
    fun offsetOf(params: Map<String, String?>): Int {
        val raw = params.nonBlank("offset")?.toIntOrNull() ?: 0
        return raw.coerceAtLeast(0)
    }

    /** Wraps a page of rows into a [RunListResponse], echoing the effective paging applied. */
    fun toResponse(
        rows: List<AgentRunRow>,
        limit: Int = SpanRepository.DEFAULT_LIMIT,
        offset: Int = 0,
    ): RunListResponse =
        RunListResponse(
            runs = rows.map(::toSummary),
            limit = limit,
            offset = offset,
        )

    /**
     * Projects an [AgentRunRow] into a [RunSummary]. The `outcome` carried here is the list-scale
     * `AgentRunRow.outcome` proxy (`agent_runs.anyLast(Outcome)`); the authoritative per-run outcome
     * is the true root-span status exposed on the `/api/runs/{id}/dag` endpoint (Plan 04, A3).
     */
    fun toSummary(row: AgentRunRow): RunSummary =
        RunSummary(
            traceId = row.traceId,
            agentName = row.agentName,
            model = row.model,
            outcome = row.outcome,
            startTime = row.startTime,
            endTime = row.endTime,
            inputTokens = row.inputTokens,
            outputTokens = row.outputTokens,
            costUsd = row.costUsd,
            maxSaturation = row.maxSaturation,
            spanCount = row.spanCount,
        )

    private fun Map<String, String?>.nonBlank(key: String): String? = this[key]?.takeIf { it.isNotBlank() }

    private fun Map<String, String?>.finiteDouble(key: String): Double? {
        val raw = nonBlank(key) ?: return null
        val value =
            raw.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid numeric value for '$key': '$raw'")
        require(value.isFinite()) { "Non-finite value for '$key': '$raw'" }
        return value
    }

    private fun Map<String, String?>.isoInstant(key: String): Instant? {
        val raw = nonBlank(key) ?: return null
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Invalid ISO-8601 instant for '$key': '$raw'", e)
        }
    }

    companion object {
        /** Hard upper bound on page size (T-04-04 DoS mitigation). */
        const val MAX_LIMIT = 500
    }
}
