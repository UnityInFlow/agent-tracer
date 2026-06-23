package io.github.unityinflow.agenttracer.storage

/**
 * The storage seam: the only type that touches ClickHouse SQL. Phase 3 (the OTLP receiver / writer)
 * feeds [writeBatch]; Phase 4 (the query API) reads [getTraceById], [listRuns], and [getPayload].
 * Nothing outside `storage/` builds ClickHouse SQL.
 *
 * All methods are `suspend` (coroutines for every async hop — CLAUDE.md) and return [Result] so
 * expected DB failures surface as [Result.failure] rather than thrown exceptions (T-02-09): the
 * Plan-04 writer owns retry/backpressure on a failed [writeBatch], and the query API can degrade
 * gracefully on a read failure.
 *
 * Every user-controlled value (trace id, span id, [RunFilter] fields) MUST be bound as a
 * `{name:Type}` placeholder by the implementation — never string-interpolated (T-02-06).
 */
interface SpanRepository {
    /**
     * Inserts [spans] and [payloads] as typed RowBinary batches. Returns [Result.success] of
     * [Unit] on success; a transient insert failure surfaces as [Result.failure] (never a thrown
     * exception, never silent success). RowBinary typed POJO inserts mean no INSERT SQL is built
     * from values (T-02-06).
     */
    suspend fun writeBatch(
        spans: List<SpanRow>,
        payloads: List<PayloadRow>,
    ): Result<Unit>

    /**
     * Returns every [SpanRow] of the trace [traceId], via the STOR-02 two-step path: read the
     * trace's time range from `trace_id_ts`, then range-scan `spans` so the primary key + partition
     * prune granules instead of full-scanning. An unknown trace yields [Result.success] of an empty
     * list.
     */
    suspend fun getTraceById(traceId: String): Result<List<SpanRow>>

    /**
     * Returns the `agent_runs` rollup rows matching [filter] — applying only its non-null fields
     * (agent / model / cost range / outcome / date range) as parameterized predicates.
     *
     * Rows are newest-first (`StartTime DESC, TraceId DESC`) and paginated: at most [limit] rows
     * starting after [offset] rows (D-05 / API-03). Both [limit] and [offset] are bound as
     * `{limit:UInt32}` / `{offset:UInt32}` placeholders by the implementation — never
     * string-interpolated (T-02-06). The repository binds whatever it is given; clamping
     * out-of-range values (default/max-cap on [limit], `offset >= 0`) is the route mapper's job
     * (Plan 02 `RunListMapper`), not the repository's. Callers with no preference pass
     * [DEFAULT_LIMIT] and offset `0`.
     */
    suspend fun listRuns(
        filter: RunFilter,
        limit: Int,
        offset: Int,
    ): Result<List<AgentRunRow>>

    companion object {
        /**
         * Default page size for [listRuns] — the single source of truth shared by the interface
         * KDoc default and the storage tests. The route mapper (Plan 02) applies this when a
         * request omits `limit`, and caps requests at its own max (≤ 500, V5).
         */
        const val DEFAULT_LIMIT: Int = 50
    }

    /**
     * Point lookup of the payload for `(traceId, spanId)` on the `payloads` sort key, with
     * truncation metadata intact (STOR-03). Returns [Result.success] of `null` when absent.
     */
    suspend fun getPayload(
        traceId: String,
        spanId: String,
    ): Result<PayloadRow?>
}
