package io.github.unityinflow.agenttracer.ingest

import com.google.protobuf.InvalidProtocolBufferException
import io.github.unityinflow.agenttracer.storage.pipeline.BackpressureSignal
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest

/** OTLP/HTTP-protobuf media type for both request and response bodies (spec-required). */
private val OTLP_PROTOBUF = ContentType("application", "x-protobuf")

/**
 * Registers the OTLP/HTTP-protobuf trace receiver: `POST /v1/traces` on the Ktor (:4318) server
 * (INGEST-01). It is the half of the receiver a stock `OtlpHttpSpanExporter` posts to. Decode and
 * response framing are reused from 03-01 ([OtlpDecoder] + [OtlpResponses]); this route is pure HTTP
 * framing, the SC3 status mapping, and the DoS body-size guard.
 *
 * **Spec/decision conformance contract:**
 * - Content-Type `application/x-protobuf` on the response (request body is read as raw bytes).
 * - **200** on full success — `ExportTraceServiceResponse` with `partial_success` UNSET.
 * - **200** on partial rejection — `partial_success.rejected_spans` + `error_message` set (D-02).
 * - **400** when the protobuf body is undecodable — the ONLY transport-level error (D-02).
 * - **429** + `Retry-After` when the pipeline is saturated — checked as the FIRST statement, BEFORE
 *   any body read/decode, so saturated load is shed immediately (D-01 fail-fast, Pitfall 4).
 * - **413** when `Content-Length` exceeds [ReceiverConfig.httpMaxBodyBytes] — the cheap pre-read DoS
 *   guard mirroring the gRPC `maxInboundMessageSize` cap (T-03-04).
 *
 * Accepted spans flow ONLY through [SpanWriter.send] (no repository call from the route, INGEST-03);
 * `send` is `suspend` and the Ktor handler is already suspending, so a brief park on the bounded
 * buffer is acceptable (D-01). The route logs nothing payload-derived (T-03-07). `val`-only, no `!!`.
 *
 * @param decoder the shared transport-agnostic OTLP decoder (03-01, D-06).
 * @param writer the single Phase-2 batching pipeline seam every accepted span is handed to.
 * @param signal the first-class backpressure signal read for the D-01 fail-fast 429.
 * @param config receiver caps + `Retry-After` (the body-size guard + 429 header source).
 */
fun Route.otlpTraces(
    decoder: OtlpDecoder,
    writer: SpanWriter,
    signal: BackpressureSignal,
    config: ReceiverConfig,
) {
    post("/v1/traces") {
        // D-01 fail-fast (Pitfall 4): saturation is the FIRST check — never read/decode under load.
        if (signal.isSaturated) {
            call.response.headers.append(HttpHeaders.RetryAfter, config.retryAfterSeconds.toString())
            call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.TooManyRequests) { ByteArray(0) }
            return@post
        }

        // T-03-04 DoS guard: reject an over-cap body on the cheap Content-Length BEFORE call.receive.
        val declaredLength = call.request.contentLength()
        if (declaredLength != null && declaredLength > config.httpMaxBodyBytes) {
            call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.PayloadTooLarge) { ByteArray(0) }
            return@post
        }

        val body = call.receive<ByteArray>()
        val request =
            try {
                ExportTraceServiceRequest.parseFrom(body)
            } catch (e: InvalidProtocolBufferException) {
                // D-02: an undecodable body is the SOLE transport-level error → 400 (client MUST NOT retry).
                call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.BadRequest) { ByteArray(0) }
                return@post
            }

        val result = decoder.decode(request)
        // INGEST-03: accepted spans flow through the batching pipeline only — no repository call here.
        result.accepted.forEach { writer.send(it.span, it.context) }

        val response = OtlpResponses.buildResponse(result)
        call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.OK) { response.toByteArray() }
    }
}
