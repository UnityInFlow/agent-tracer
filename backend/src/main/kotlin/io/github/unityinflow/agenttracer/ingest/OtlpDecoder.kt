package io.github.unityinflow.agenttracer.ingest

import com.google.protobuf.ByteString
import io.github.unityinflow.agenttracer.convention.AgentSpanExtractor
import io.github.unityinflow.agenttracer.convention.AttrView
import io.github.unityinflow.agenttracer.storage.SpanContext
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status
import java.time.Instant

/**
 * The single, transport-agnostic OTLP decoder (D-06): walks an `ExportTraceServiceRequest`
 * (ResourceSpans → ScopeSpans → Span) once and produces `(AgentSpan, SpanContext)` envelopes plus
 * partial-success accounting. Both the Ktor HTTP route and the gRPC service call THIS decoder, so
 * the `SpanWriter` hand-off and `partial_success` math are implemented exactly once.
 *
 * **Store-all, unified path (D-03):** EVERY span — agent and non-agent alike — runs through the
 * Phase-1 [AgentSpanExtractor] here. A non-agent span (no `gen_ai.*` attributes) simply yields an
 * [io.github.unityinflow.agenttracer.domain.AgentSpan] whose agent-specific fields are null/default
 * while the raw attributes are preserved downstream; it is NOT dropped and there is no separate
 * generic-span branch or table.
 *
 * **Tolerant per-span (D-02):** a span is rejected ONLY when it cannot become a valid row at all —
 * i.e. its trace id or span id is empty. There is NO size check here: oversized prompt/completion
 * payloads are truncated downstream by the Phase-2 `AgentSpanRowMapper` 256 KB cap, never rejected.
 *
 * **Never throws:** mirroring [AgentSpanExtractor]/[AttrView], the decoder never propagates an
 * exception on malformed input — one bad span increments [DecodeResult.rejectedSpans] (T-03-01).
 */
class OtlpDecoder {
    /** Decodes [request] into a [DecodeResult]. Deterministic — identical for the same input regardless of caller (D-06). */
    fun decode(request: ExportTraceServiceRequest): DecodeResult {
        val accepted = ArrayList<SpanWriter.SpanEnvelope>()
        var rejected = 0L
        var firstError: String? = null

        for (resourceSpans in request.resourceSpansList) {
            // hasResource() guards the proto default — absent resource → empty view (never throws).
            val resourceView =
                if (resourceSpans.hasResource()) {
                    AttrView.of(resourceSpans.resource.attributesList)
                } else {
                    AttrView.empty()
                }
            for (scopeSpans in resourceSpans.scopeSpansList) {
                for (span in scopeSpans.spansList) {
                    val envelope = toEnvelope(span, resourceView)
                    if (envelope != null) {
                        accepted.add(envelope)
                    } else {
                        rejected++
                        if (firstError == null) {
                            firstError = "span rejected: missing/invalid trace or span id"
                        }
                    }
                }
            }
        }
        return DecodeResult(accepted, rejected, firstError)
    }

    /**
     * Maps one OTLP [span] + its [resourceView] into a [SpanWriter.SpanEnvelope], or `null` when the
     * span cannot become a valid row (D-02: empty trace/span id — the SOLE rejection rule, no size check).
     */
    private fun toEnvelope(
        span: Span,
        resourceView: AttrView,
    ): SpanWriter.SpanEnvelope? {
        // D-02: minimum viable identity is a non-empty trace id (16 bytes) and span id (8 bytes).
        if (span.traceId.isEmpty || span.spanId.isEmpty) return null

        val attrs = AttrView.of(span.attributesList)
        val isError = span.status.code == Status.StatusCode.STATUS_CODE_ERROR
        // D-03 store-all: every span goes through the extractor; non-agent spans get null/default agent fields.
        val agentSpan = AgentSpanExtractor.extract(attrs, resourceView, otelStatusIsError = isError)

        val context =
            SpanContext(
                // D-07: hex-encode the raw OTLP id bytes to match the Phase-2 storage column format
                // BYTE-FOR-BYTE — lowercase, no 0x prefix, empty parent = "". Any drift silently breaks
                // getTraceById (inserts succeed, lookups return empty — Pitfall 1).
                traceId = span.traceId.toHex(),
                spanId = span.spanId.toHex(),
                parentSpanId = if (span.parentSpanId.isEmpty) "" else span.parentSpanId.toHex(),
                // A2: SpanContext.timestamp is the span START time (the schema's order/partition key).
                timestamp = Instant.ofEpochSecond(0, span.startTimeUnixNano),
            )
        // Carry the OTLP envelope display names: Span.name + resource `service.name`. AgentSpan is
        // attributes-only and SpanContext is identity-only — without these the SpanName/ServiceName
        // columns land empty for every ingested span.
        return SpanWriter.SpanEnvelope(
            agentSpan,
            context,
            spanName = span.name,
            serviceName = resourceView.string("service.name") ?: "",
        )
    }
}

/**
 * Lowercase, no-prefix, per-byte zero-padded hex of these raw OTLP id bytes.
 *
 * This MUST match the Phase-2 storage column format (`storage/SpanFixtures` DEFAULT_TRACE_ID etc.,
 * D-07) exactly — `getTraceById` binds the id as a verbatim String, so any casing/width/prefix
 * drift makes trace-by-id lookup return empty while inserts still succeed (Pitfall 1).
 */
private fun ByteString.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
