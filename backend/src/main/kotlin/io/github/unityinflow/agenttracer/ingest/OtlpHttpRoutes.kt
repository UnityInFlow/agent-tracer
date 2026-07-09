package io.github.unityinflow.agenttracer.ingest

import com.google.protobuf.InvalidProtocolBufferException
import io.github.unityinflow.agenttracer.storage.pipeline.BackpressureSignal
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import java.io.ByteArrayOutputStream

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
 * - **413** when the body exceeds [ReceiverConfig.httpMaxBodyBytes] — enforced BOTH on the cheap
 *   `Content-Length` pre-read fast-path AND on the actual bytes read off the request channel, so a
 *   `Transfer-Encoding: chunked` / length-omitted upload cannot stream past the cap and defeat the
 *   bound. This is the real symmetry with the gRPC `maxInboundMessageSize` cap (T-03-04, WR-03): the
 *   body is never fully buffered past the limit — the read aborts the instant it crosses it.
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

        // T-03-04 DoS guard, fast-path: reject an over-cap body on the cheap Content-Length BEFORE any read.
        val declaredLength = call.request.contentLength()
        if (declaredLength != null && declaredLength > config.httpMaxBodyBytes) {
            call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.PayloadTooLarge) { ByteArray(0) }
            return@post
        }

        // WR-03: enforce the cap on the ACTUAL bytes read — a chunked / length-omitted body would slip
        // past the Content-Length fast-path above, so read the channel with a hard cap and 413 the
        // instant it is exceeded (the body is never fully buffered past the limit).
        val body =
            readBodyCapped(call.receiveChannel(), config.httpMaxBodyBytes)
                ?: run {
                    call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.PayloadTooLarge) { ByteArray(0) }
                    return@post
                }

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
        result.accepted.forEach { writer.send(it.span, it.context, it.spanName, it.serviceName) }

        val response = OtlpResponses.buildResponse(result)
        call.respondBytes(OTLP_PROTOBUF, HttpStatusCode.OK) { response.toByteArray() }
    }
}

/**
 * Reads [channel] fully into a `ByteArray`, but ABORTS and returns `null` the instant the accumulated
 * size would exceed [maxBytes] — so a `Transfer-Encoding: chunked` / length-omitted body can never
 * buffer past the cap (WR-03). Reads incrementally in [READ_BUFFER_BYTES] chunks; `null` signals the
 * caller to respond `413`. `val`-only, no `!!`; coroutine-suspending channel reads (no blocking).
 */
private suspend fun readBodyCapped(
    channel: ByteReadChannel,
    maxBytes: Int,
): ByteArray? {
    val sink = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break // end of stream
        if (read == 0) continue
        if (sink.size().toLong() + read > maxBytes) return null // over cap → caller emits 413
        sink.write(buffer, 0, read)
    }
    return sink.toByteArray()
}

/** Incremental read-chunk size for the capped body read (16 KiB — well under any sane body cap). */
private const val READ_BUFFER_BYTES = 16 * 1024
