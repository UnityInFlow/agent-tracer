package io.github.unityinflow.agenttracer.ingest

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status

/**
 * Builders for hand-crafted OTLP `ExportTraceServiceRequest`/`ResourceSpans`/`Span` test inputs.
 *
 * Reuse the `convention/SpanFixtures` `stringAttr`/`intAttr`/`doubleAttr` KeyValue helpers to build
 * span/resource attributes — this file only assembles the OTLP *tree* (RS → SS → Span) around them.
 * Ids are supplied as hex strings and converted to raw bytes via [hexToBytes] so a test can pin a
 * decoded id against the Phase-2 `storage/SpanFixtures` hex constants (D-07 round-trip).
 */
object OtlpRequestFixtures {
    /** Decodes a lowercase hex id string into the raw OTLP `ByteString` (the inverse of the decoder's `toHex`). */
    fun hexToBytes(hex: String): ByteString {
        require(hex.length % 2 == 0) { "hex length must be even, got ${hex.length}" }
        val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        return ByteString.copyFrom(bytes)
    }

    /**
     * Builds an OTLP [Span]. [traceIdHex]/[spanIdHex] are converted to raw bytes; an empty
     * [parentSpanIdHex] leaves the parent id as the proto-default empty `ByteString`. Pass
     * `traceIdHex = ""` / `spanIdHex = ""` to model a rejectable (empty-id) span.
     */
    fun spanProto(
        traceIdHex: String,
        spanIdHex: String,
        parentSpanIdHex: String = "",
        startUnixNano: Long = 0L,
        attrs: List<KeyValue> = emptyList(),
        isError: Boolean = false,
    ): Span {
        val builder =
            Span
                .newBuilder()
                .setTraceId(if (traceIdHex.isEmpty()) ByteString.EMPTY else hexToBytes(traceIdHex))
                .setSpanId(if (spanIdHex.isEmpty()) ByteString.EMPTY else hexToBytes(spanIdHex))
                .setStartTimeUnixNano(startUnixNano)
                .addAllAttributes(attrs)
        if (parentSpanIdHex.isNotEmpty()) {
            builder.parentSpanId = hexToBytes(parentSpanIdHex)
        }
        if (isError) {
            builder.status = Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build()
        }
        return builder.build()
    }

    /** Wraps [spans] in a single [ResourceSpans]/[ScopeSpans]; [resourceAttrs] `null` ⇒ no resource set (decoder uses AttrView.empty()). */
    fun resourceSpans(
        resourceAttrs: List<KeyValue>?,
        spans: List<Span>,
    ): ResourceSpans {
        val scopeSpans = ScopeSpans.newBuilder().addAllSpans(spans).build()
        val builder = ResourceSpans.newBuilder().addScopeSpans(scopeSpans)
        if (resourceAttrs != null) {
            builder.resource = Resource.newBuilder().addAllAttributes(resourceAttrs).build()
        }
        return builder.build()
    }

    /** Assembles an [ExportTraceServiceRequest] from one or more [ResourceSpans]. */
    fun exportRequest(vararg resourceSpans: ResourceSpans): ExportTraceServiceRequest =
        ExportTraceServiceRequest.newBuilder().addAllResourceSpans(resourceSpans.toList()).build()
}
