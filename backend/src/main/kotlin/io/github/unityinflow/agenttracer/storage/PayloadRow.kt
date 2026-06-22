package io.github.unityinflow.agenttracer.storage

import java.time.Instant

/**
 * client-v2 `register()` POJO mirroring the `payloads` DDL columns one-to-one (002_payloads.sql).
 *
 * Mutable `var` + no-arg constructor for the same framework reason documented on [SpanRow]:
 * client-v2 0.9.8 binds columns to JavaBean getters/setters and instantiates rows via a no-arg
 * constructor on the read path. Mutability is isolated to this row class.
 *
 * Keyed `(TraceId, SpanId)` for the STOR-03 point lookup; [timestamp] is carried only to align the
 * 14-day TTL with `spans` (D-05). [payloadTruncated]/[payloadOriginalSizeBytes] preserve the
 * Phase-1 D-13 256 KB-per-field truncation contract so the cap round-trips intact. Identity comes
 * from [SpanContext], the bodies from [io.github.unityinflow.agenttracer.domain.AgentSpan].
 */
class PayloadRow {
    var traceId: String = ""
    var spanId: String = ""
    var timestamp: Instant = Instant.EPOCH
    var inputPayload: String = ""
    var outputPayload: String = ""
    var systemInstructions: String = ""
    var payloadTruncated: Boolean = false
    var payloadOriginalSizeBytes: Long? = null

    companion object {
        /**
         * Projects an [AgentSpan]'s payload bodies + truncation metadata plus its [SpanContext]
         * identity into a [PayloadRow]. Null payload bodies collapse to `''` for the non-Nullable
         * `String` columns; the original-size byte count keeps `null` when unmeasured.
         */
        fun from(
            span: io.github.unityinflow.agenttracer.domain.AgentSpan,
            context: SpanContext,
        ): PayloadRow =
            PayloadRow().apply {
                traceId = context.traceId
                spanId = context.spanId
                timestamp = context.timestamp
                inputPayload = span.inputPayload ?: ""
                outputPayload = span.outputPayload ?: ""
                systemInstructions = span.systemInstructions ?: ""
                payloadTruncated = span.payloadTruncated ?: false
                payloadOriginalSizeBytes = span.payloadOriginalSizeBytes
            }
    }
}
