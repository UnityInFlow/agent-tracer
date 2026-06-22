package io.github.unityinflow.agenttracer.storage

import java.time.Instant

/**
 * client-v2 `register()` POJO mirroring the `spans` DDL columns one-to-one (001_spans.sql).
 *
 * **Why a mutable `var` POJO with a no-arg constructor (and not an immutable `data class`):**
 * the ClickHouse client-v2 0.9.8 [com.clickhouse.client.api.Client.register] /
 * `POJOSerDe` machinery binds DDL columns to JavaBean accessors via reflection
 * ([com.clickhouse.client.api.metadata.DefaultColumnToMethodMatchingStrategy]) — it compiles a
 * field *serializer* from each `getXxx` getter AND a *deserializer* into each `setXxx` setter,
 * and instantiates rows through a no-arg constructor when reading. A Kotlin `data class` with
 * `val`s exposes getters but no setters and no no-arg constructor, so it cannot be registered for
 * the read (deserialize) path. The mutability is therefore a framework requirement, isolated to
 * the row classes only (CLAUDE.md `var` rule exception, documented per the plan).
 *
 * Column→getter matching is case-insensitive after prefix stripping, so `TraceId` ↔ `traceId`
 * (getter `getTraceId`) line up. Identity columns (`Timestamp`/`TraceId`/`SpanId`/`ParentSpanId`)
 * are sourced from [SpanContext] (never empty-string), the typed agent dimensions project from
 * [io.github.unityinflow.agenttracer.domain.AgentSpan], and the five aggregate-feeding metric
 * columns + `PayloadOriginalSizeBytes` are the only Nullable columns → boxed nullable Kotlin types
 * (`Long?`/`Double?`/`Float?`), matching D-01.
 */
class SpanRow {
    // ── OTLP envelope identity (from SpanContext; never empty) ──
    var timestamp: Instant = Instant.EPOCH
    var traceId: String = ""
    var spanId: String = ""
    var parentSpanId: String = ""
    var spanName: String = ""
    var serviceName: String = ""
    var duration: Long = 0
    var statusCode: String = ""

    // ── promoted typed agent dimensions (D-02: sentinel '' / LowCardinality, NOT Nullable) ──
    var agentName: String = ""
    var agentId: String = ""
    var provider: String = ""
    var model: String = ""
    var skillName: String = ""
    var skillVersion: String = ""
    var toolCallId: String = ""
    var conversationId: String = ""
    var operation: String = ""
    var outcome: String = ""
    var finishReasons: List<String> = emptyList()
    var schemaVersion: String = ""

    // ── aggregate-feeding metric columns (D-01: Nullable → boxed nullable types) ──
    var inputTokens: Long? = null
    var outputTokens: Long? = null
    var costUsd: Double? = null
    var contextSaturationPct: Float? = null
    var planStep: Long? = null

    // ── payload truncation metadata (D-03; bodies live in payloads, D-04) ──
    var payloadTruncated: Boolean = false
    var payloadOriginalSizeBytes: Long? = null

    // ── non-agent leftovers only (Pitfall 3) ──
    var spanAttributes: Map<String, String> = emptyMap()
    var resourceAttributes: Map<String, String> = emptyMap()

    companion object {
        /**
         * Projects an [AgentSpan] (OTLP attributes) plus its [SpanContext] (envelope identity)
         * into a [SpanRow]. Nullable [AgentSpan] dimensions collapse to the schema sentinel `''`
         * for the non-Nullable typed columns (D-02); the five Nullable metric columns keep `null`
         * as "unmeasured" (D-01). Payload bodies are intentionally NOT carried here — they live in
         * [PayloadRow] (D-04).
         */
        fun from(
            span: io.github.unityinflow.agenttracer.domain.AgentSpan,
            context: SpanContext,
            spanName: String = "",
            serviceName: String = "",
            duration: Long = 0,
            statusCode: String = "",
            spanAttributes: Map<String, String> = emptyMap(),
            resourceAttributes: Map<String, String> = emptyMap(),
        ): SpanRow =
            SpanRow().apply {
                timestamp = context.timestamp
                traceId = context.traceId
                this.spanId = context.spanId
                parentSpanId = context.parentSpanId
                this.spanName = spanName
                this.serviceName = serviceName
                this.duration = duration
                this.statusCode = statusCode
                agentName = span.agentName ?: ""
                agentId = span.agentId ?: ""
                provider = span.provider ?: ""
                model = span.model ?: ""
                skillName = span.skillName ?: ""
                skillVersion = span.skillVersion ?: ""
                toolCallId = span.toolCallId ?: ""
                conversationId = span.conversationId ?: ""
                operation = span.operation?.name ?: ""
                outcome = span.outcome.name
                finishReasons = span.finishReasons ?: emptyList()
                schemaVersion = span.schemaVersion ?: ""
                inputTokens = span.inputTokens
                outputTokens = span.outputTokens
                costUsd = span.costUsd
                contextSaturationPct = span.saturationPct?.toFloat()
                planStep = span.planStep
                payloadTruncated = span.payloadTruncated ?: false
                payloadOriginalSizeBytes = span.payloadOriginalSizeBytes
                this.spanAttributes = spanAttributes
                this.resourceAttributes = resourceAttributes
            }
    }
}
