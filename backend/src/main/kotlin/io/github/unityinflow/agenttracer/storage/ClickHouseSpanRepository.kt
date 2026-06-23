package io.github.unityinflow.agenttracer.storage

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.data_formats.ClickHouseBinaryFormatReader
import com.clickhouse.client.api.insert.InsertSettings
import com.clickhouse.client.api.query.GenericRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

/**
 * client-v2 [SpanRepository] implementation — the sole holder of ClickHouse SQL (the storage seam).
 *
 * **Writes** ([writeBatch]) go through typed RowBinary POJO inserts (`client.insert(table, list)`),
 * so no INSERT SQL is ever built from values (T-02-06). [SpanRow]/[PayloadRow] are `register()`-ed
 * once (lazily, idempotently) against their table schemas before the first insert.
 *
 * **Reads** bind every user-controlled value as a `{name:Type}` placeholder — never string
 * interpolation (T-02-06):
 *  - [getTraceById] does the STOR-02 two-step lookup (range from `trace_id_ts FINAL`, then a bounded
 *    `spans` range scan so granules prune; no `FINAL` on the hot spans scan per the anti-pattern).
 *  - [listRuns] assembles a `WHERE` clause from only the non-null [RunFilter] fields over
 *    `agent_runs FINAL`, each value a placeholder.
 *  - [getPayload] is a parameterized point lookup on the `payloads` `(TraceId, SpanId)` key.
 *
 * Every async hop is bridged with `.await()` (coroutines — CLAUDE.md); no blocking `.get()`, no
 * `Thread.sleep`. Expected DB failures are caught and returned as [Result.failure] (T-02-09).
 *
 * @param client an open client-v2 [Client] (built by [ClickHouseClientFactory]); the caller owns
 *   its lifecycle.
 */
class ClickHouseSpanRepository(
    private val client: Client,
) : SpanRepository {
    @Volatile
    private var registered = false

    /**
     * Registers the [SpanRow]/[PayloadRow] POJOs against their live table schemas exactly once.
     * `register` requires the tables to already exist (the MigrationRunner runs first at startup);
     * it is idempotent and cheap, so a double-checked guard keeps it off the hot insert path.
     */
    private fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            client.register(SpanRow::class.java, client.getTableSchema(SPANS_TABLE))
            client.register(PayloadRow::class.java, client.getTableSchema(PAYLOADS_TABLE))
            registered = true
        }
    }

    override suspend fun writeBatch(
        spans: List<SpanRow>,
        payloads: List<PayloadRow>,
    ): Result<Unit> =
        runCatching {
            ensureRegistered()
            // Typed RowBinary POJO inserts — the client picks the efficient binary format; no SQL
            // is built from values. Empty lists are skipped (client-v2 rejects empty inserts).
            if (spans.isNotEmpty()) {
                client.insert(SPANS_TABLE, spans, InsertSettings()).await()
            }
            if (payloads.isNotEmpty()) {
                client.insert(PAYLOADS_TABLE, payloads, InsertSettings()).await()
            }
            Unit
        }.onFailure { logger.warn(it) { "writeBatch failed (${spans.size} spans, ${payloads.size} payloads)" } }

    override suspend fun getTraceById(traceId: String): Result<List<SpanRow>> =
        runCatching {
            // STOR-02 step 1: time range for the trace from the MV (FINAL: aggregating table).
            val range =
                client
                    .queryAll(
                        "SELECT MinTime AS lo, MaxTime AS hi FROM $TRACE_ID_TS_TABLE FINAL WHERE TraceId = {tid:String}",
                        mapOf("tid" to traceId),
                    ).firstOrNull()
                    ?: return@runCatching emptyList()

            val lo = TS_FORMAT.format(range.getInstant("lo"))
            val hi = TS_FORMAT.format(range.getInstant("hi"))

            // STOR-02 step 2: bounded range scan so the primary key + partition prune granules.
            // No FINAL here — this is the hot path (anti-pattern: don't sprinkle FINAL on spans).
            val response =
                client
                    .query(
                        """
                        SELECT * FROM $SPANS_TABLE
                        WHERE TraceId = {tid:String}
                          AND Timestamp BETWEEN {lo:DateTime64(9)} AND {hi:DateTime64(9)}
                        """.trimIndent(),
                        mapOf("tid" to traceId, "lo" to lo, "hi" to hi),
                    ).await()

            response.use { resp ->
                val reader = client.newBinaryFormatReader(resp)
                buildList {
                    while (reader.hasNext()) {
                        reader.next()
                        add(reader.toSpanRow())
                    }
                }
            }
        }.onFailure { logger.warn(it) { "getTraceById failed for trace=$traceId" } }

    override suspend fun listRuns(
        filter: RunFilter,
        limit: Int,
        offset: Int,
    ): Result<List<AgentRunRow>> =
        runCatching {
            // Build the WHERE from ONLY non-null filter fields; every value is a {name:Type}
            // placeholder (T-02-06) — predicate fragments are constant strings, values never inlined.
            val predicates = mutableListOf<String>()
            val params = mutableMapOf<String, Any>()
            filter.agentName?.let {
                predicates += "AgentName = {agentName:String}"
                params["agentName"] = it
            }
            filter.model?.let {
                predicates += "Model = {model:String}"
                params["model"] = it
            }
            filter.costMin?.let {
                predicates += "CostUsd >= {costMin:Float64}"
                params["costMin"] = it
            }
            filter.costMax?.let {
                predicates += "CostUsd <= {costMax:Float64}"
                params["costMax"] = it
            }
            filter.outcome?.let {
                predicates += "Outcome = {outcome:String}"
                params["outcome"] = it
            }
            filter.dateFrom?.let {
                predicates += "StartTime >= {dateFrom:DateTime64(9)}"
                params["dateFrom"] = TS_FORMAT.format(it)
            }
            filter.dateTo?.let {
                predicates += "StartTime <= {dateTo:DateTime64(9)}"
                params["dateTo"] = TS_FORMAT.format(it)
            }

            // Pagination bounds are bound as {limit:UInt32}/{offset:UInt32} placeholders, exactly
            // like the predicate values above — never interpolated (T-02-06). Clamping is the route
            // mapper's responsibility (Plan 02); the repository binds whatever it is given.
            params["limit"] = limit
            params["offset"] = offset

            val where = if (predicates.isEmpty()) "" else "WHERE " + predicates.joinToString(" AND ")
            val sql =
                """
                SELECT TraceId, AgentName, Model, Outcome, StartTime, EndTime,
                       InputTokens, OutputTokens, CostUsd, MaxSaturation, SpanCount
                FROM $AGENT_RUNS_TABLE FINAL
                $where
                ORDER BY StartTime DESC, TraceId DESC
                LIMIT {limit:UInt32} OFFSET {offset:UInt32}
                """.trimIndent()

            client
                .queryAll(sql, params)
                .map { it.toAgentRunRow() }
        }.onFailure { logger.warn(it) { "listRuns failed for filter=$filter" } }

    override suspend fun getPayload(
        traceId: String,
        spanId: String,
    ): Result<PayloadRow?> =
        runCatching {
            val response =
                client
                    .query(
                        """
                        SELECT TraceId, SpanId, Timestamp, InputPayload, OutputPayload,
                               SystemInstructions, PayloadTruncated, PayloadOriginalSizeBytes
                        FROM $PAYLOADS_TABLE
                        WHERE TraceId = {tid:String} AND SpanId = {sid:String}
                        """.trimIndent(),
                        mapOf("tid" to traceId, "sid" to spanId),
                    ).await()

            response.use { resp ->
                val reader = client.newBinaryFormatReader(resp)
                if (!reader.hasNext()) {
                    null
                } else {
                    reader.next()
                    reader.toPayloadRow()
                }
            }
        }.onFailure { logger.warn(it) { "getPayload failed for trace=$traceId span=$spanId" } }

    /** Maps the current binary-reader row into a [SpanRow], preserving Nullable-column nulls. */
    private fun ClickHouseBinaryFormatReader.toSpanRow(): SpanRow =
        SpanRow().apply {
            timestamp = getInstant("Timestamp")
            traceId = getString("TraceId")
            spanId = getString("SpanId")
            parentSpanId = getString("ParentSpanId")
            spanName = getString("SpanName")
            serviceName = getString("ServiceName")
            duration = getLong("Duration")
            statusCode = getString("StatusCode")
            agentName = getString("AgentName")
            agentId = getString("AgentId")
            provider = getString("Provider")
            model = getString("Model")
            skillName = getString("SkillName")
            skillVersion = getString("SkillVersion")
            toolCallId = getString("ToolCallId")
            conversationId = getString("ConversationId")
            operation = getString("Operation")
            outcome = getString("Outcome")
            finishReasons = getList("FinishReasons")
            schemaVersion = getString("SchemaVersion")
            // readValue returns null for SQL NULL — preserves the D-01 Nullable distinction.
            inputTokens = readValue<Any?>("InputTokens")?.let { (it as Number).toLong() }
            outputTokens = readValue<Any?>("OutputTokens")?.let { (it as Number).toLong() }
            costUsd = readValue<Any?>("CostUsd")?.let { (it as Number).toDouble() }
            contextSaturationPct = readValue<Any?>("ContextSaturationPct")?.let { (it as Number).toFloat() }
            planStep = readValue<Any?>("PlanStep")?.let { (it as Number).toLong() }
            payloadTruncated = getBoolean("PayloadTruncated")
            payloadOriginalSizeBytes = readValue<Any?>("PayloadOriginalSizeBytes")?.let { (it as Number).toLong() }
            spanAttributes = readMapColumn("SpanAttributes")
            resourceAttributes = readMapColumn("ResourceAttributes")
        }

    /** Reads a `Map(String, String)` column; client-v2 surfaces it via [readValue] as a Map. */
    private fun ClickHouseBinaryFormatReader.readMapColumn(column: String): Map<String, String> {
        val raw = readValue<Any?>(column) ?: return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val map = raw as Map<Any?, Any?>
        return map.entries.associate { (k, v) -> k.toString() to v.toString() }
    }

    /** Maps the current binary-reader row into a [PayloadRow], preserving the Nullable size. */
    private fun ClickHouseBinaryFormatReader.toPayloadRow(): PayloadRow =
        PayloadRow().apply {
            traceId = getString("TraceId")
            spanId = getString("SpanId")
            timestamp = getInstant("Timestamp")
            inputPayload = getString("InputPayload")
            outputPayload = getString("OutputPayload")
            systemInstructions = getString("SystemInstructions")
            payloadTruncated = getBoolean("PayloadTruncated")
            payloadOriginalSizeBytes = readValue<Any?>("PayloadOriginalSizeBytes")?.let { (it as Number).toLong() }
        }

    /** Maps a finalized `agent_runs` [GenericRecord] (all SimpleAggregateFunction values). */
    private fun GenericRecord.toAgentRunRow(): AgentRunRow =
        AgentRunRow(
            traceId = getString("TraceId"),
            agentName = getString("AgentName"),
            model = getString("Model"),
            outcome = getString("Outcome"),
            startTime = getInstant("StartTime"),
            endTime = getInstant("EndTime"),
            inputTokens = getLong("InputTokens"),
            outputTokens = getLong("OutputTokens"),
            costUsd = getDouble("CostUsd"),
            maxSaturation = getFloat("MaxSaturation"),
            spanCount = getLong("SpanCount"),
        )

    private companion object {
        const val SPANS_TABLE = "spans"
        const val PAYLOADS_TABLE = "payloads"
        const val TRACE_ID_TS_TABLE = "trace_id_ts"
        const val AGENT_RUNS_TABLE = "agent_runs"

        /** ClickHouse DateTime64(9) literal format for parameterized time-range bounds. */
        val TS_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC)
    }
}
