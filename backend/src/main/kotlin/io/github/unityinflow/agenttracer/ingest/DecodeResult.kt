package io.github.unityinflow.agenttracer.ingest

import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter

/**
 * The transport-agnostic result of decoding one `ExportTraceServiceRequest` (D-06).
 *
 * Carries the spans that became valid write envelopes plus the partial-success accounting
 * both transports translate into their own response shape. The decode itself is tolerant
 * per-span (D-02): one unmappable span never fails the batch — it is counted here instead.
 *
 * **Invariant:** `rejectedSpans == 0` ⇒ `errorMessage == null` ⇒ the caller leaves OTLP
 * `partial_success` UNSET (spec-conformant full success). When `rejectedSpans > 0` the
 * [errorMessage] is non-null and human-readable.
 *
 * @property accepted the spans that decoded into a writable [SpanWriter.SpanEnvelope].
 * @property rejectedSpans count of spans dropped because they could not become a valid row
 *   (empty trace/span id only — D-02); oversized payloads are never counted here.
 * @property errorMessage human-readable summary of the rejection, or `null` on full success.
 */
data class DecodeResult(
    val accepted: List<SpanWriter.SpanEnvelope>,
    val rejectedSpans: Long,
    val errorMessage: String?,
)
