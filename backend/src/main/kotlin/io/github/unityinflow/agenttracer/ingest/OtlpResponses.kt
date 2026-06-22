package io.github.unityinflow.agenttracer.ingest

import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse

/**
 * Pure, stateless builder of the OTLP `ExportTraceServiceResponse` shared by both transports
 * (D-06) — the single place partial-success accounting is rendered into the wire response.
 *
 * Spec-conformant: on full success (`rejectedSpans == 0`) the response leaves `partial_success`
 * UNSET; on partial rejection it sets `rejected_spans` plus a non-blank `error_message`. The HTTP
 * route returns this with status 200 and the gRPC service hands it to `onNext` — the response body
 * is identical, only the transport framing differs.
 */
object OtlpResponses {
    /** Renders [result] into an `ExportTraceServiceResponse`, setting `partial_success` only when spans were rejected. */
    fun buildResponse(result: DecodeResult): ExportTraceServiceResponse {
        val builder = ExportTraceServiceResponse.newBuilder()
        if (result.rejectedSpans > 0) {
            builder.partialSuccess =
                ExportTracePartialSuccess
                    .newBuilder()
                    .setRejectedSpans(result.rejectedSpans)
                    .setErrorMessage(result.errorMessage ?: "some spans were rejected")
                    .build()
        }
        // rejectedSpans == 0 → leave partial_success UNSET (spec-required full success).
        return builder.build()
    }
}
