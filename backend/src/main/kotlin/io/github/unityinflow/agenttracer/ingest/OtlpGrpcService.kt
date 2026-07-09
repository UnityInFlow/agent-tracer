package io.github.unityinflow.agenttracer.ingest

import io.github.unityinflow.agenttracer.storage.pipeline.BackpressureSignal
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.grpc.stub.StreamObserver
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.grpc.Status as GrpcStatus

/**
 * The OTLP/gRPC trace receiver (INGEST-02): implements the pre-generated
 * [TraceServiceGrpc.TraceServiceImplBase] (shipped by `opentelemetry-proto`, **no protoc pipeline**)
 * and overrides [export] for the standalone `io.grpc.Server` on :4317. It is the gRPC half a stock
 * `OtlpGrpcSpanExporter` (and KORE) posts to.
 *
 * **Shared decode/response (D-06):** decode and `partial_success` framing are reused unchanged from
 * 03-01 — this service calls the SAME [OtlpDecoder] and [OtlpResponses.buildResponse] as the HTTP
 * route ([Route.otlpTraces]). Only the transport framing differs; accepted spans flow ONLY through
 * [SpanWriter.send] (INGEST-03), never a repository call here.
 *
 * **Callback → suspend coroutine bridge (Pitfall 2):** [export] is callback-style (`StreamObserver`)
 * but [SpanWriter.send] is `suspend`. The bridge `launch`es the decode/send/complete on the
 * per-call request [scope] and completes the observer from inside the coroutine. It NEVER calls
 * `runBlocking`/`Thread.sleep`/raw threads inside [export] (CLAUDE.md raw-thread ban): blocking a
 * grpc-netty event-loop thread stalls the transport and looks like silent data loss.
 *
 * **Shutdown ordering (CR-01/CR-02):** [scope] is the gRPC service's OWN per-call request scope —
 * distinct from the [SpanWriter]'s consumer scope. The bootstrap drains in the order: stop the
 * gRPC/HTTP ports → join THIS scope so every in-flight `export` coroutine has finished its
 * `writer.send` → THEN `writer.drainAndClose()`. That barrier is why an accepted span can never hit
 * a closed intake channel. If a call is admitted after the scope is no longer active (the shutdown
 * window), [export] fails fast with `UNAVAILABLE` instead of silently `launch`ing a no-op coroutine
 * that would leave the client hanging until its deadline.
 *
 * **Saturation → `RESOURCE_EXHAUSTED` (D-01):** when [BackpressureSignal.isSaturated] is `true`,
 * [export] fails FAST with `io.grpc.Status.RESOURCE_EXHAUSTED` BEFORE launching/decoding — no body
 * read. D-01 deliberately picks `RESOURCE_EXHAUSTED` over the spec's `UNAVAILABLE` (Pitfall 6): both
 * are retryable in the OTel SDK/Collector (the client backs off), and `RESOURCE_EXHAUSTED` carries
 * the more precise "I am overloaded, slow down" semantics for a saturated ingest pipeline.
 *
 * **Type-collision guard (PATTERNS):** `io.grpc.Status` (the transport status used in `onError`) is
 * imported aliased as [GrpcStatus] to avoid silent mis-binding against
 * `io.opentelemetry.proto.trace.v1.Status` (the span status the decoder reads) — both are in scope
 * across the `ingest` package.
 *
 * **Undecodable body (A3):** unlike the HTTP route (which calls `parseFrom` itself), gRPC's own
 * protobuf marshaller deserializes the [ExportTraceServiceRequest] BEFORE [export] is invoked — a
 * malformed wire body is rejected by grpc-java with `INVALID_ARGUMENT` automatically. There is thus
 * NO manual parse-failure branch here; the `catch` maps any internal fault to `INTERNAL` (D-02: one
 * bad span never fails the batch — that accounting lives in [OtlpDecoder]).
 *
 * **Inbound size (SC2):** the standalone `io.grpc.Server` that hosts this service (built in 03-04)
 * MUST set `.maxInboundMessageSize(ReceiverConfig.grpcMaxInboundBytes)` (32 MiB) so prompt-heavy
 * spans over gRPC's 4 MiB default cap are accepted; over-cap messages are rejected by grpc-java with
 * `RESOURCE_EXHAUSTED` before reaching [export] (T-03-08). The knob value originates in
 * [ReceiverConfig] (03-01).
 *
 * `val`-only, no `!!`.
 *
 * @param decoder the shared transport-agnostic OTLP decoder (03-01, D-06).
 * @param writer the single Phase-2 batching pipeline seam every accepted span is handed to.
 * @param signal the first-class backpressure signal read for the D-01 fail-fast RESOURCE_EXHAUSTED.
 * @param scope the per-call request [CoroutineScope] the decode/send bridge is launched on — distinct
 *   from the writer's consumer scope so shutdown can join in-flight calls BEFORE draining the writer
 *   (CR-01/CR-02). Must outlive no longer than a single drain cycle.
 */
class OtlpGrpcService(
    private val decoder: OtlpDecoder,
    private val writer: SpanWriter,
    private val signal: BackpressureSignal,
    private val scope: CoroutineScope,
) : TraceServiceGrpc.TraceServiceImplBase() {
    /**
     * Handles one OTLP/gRPC `Export` call. Fails fast with `RESOURCE_EXHAUSTED` under saturation
     * (D-01, no decode); otherwise bridges the callback observer to the `suspend` writer by launching
     * on [scope] — never blocking the gRPC callback thread (Pitfall 2).
     */
    override fun export(
        request: ExportTraceServiceRequest,
        responseObserver: StreamObserver<ExportTraceServiceResponse>,
    ) {
        // D-01 fail-fast (Pitfall 4/6): saturation is the FIRST check — shed load before any decode.
        if (signal.isSaturated) {
            responseObserver.onError(
                GrpcStatus.RESOURCE_EXHAUSTED
                    .withDescription("ingest pipeline saturated; retry with backoff")
                    .asRuntimeException(),
            )
            return
        }

        // CR-02: launch-after-cancel is a silent no-op (the coroutine never runs, the observer is
        // never completed, the client hangs to its deadline, the span vanishes). If the request scope
        // is shutting down, refuse the call cleanly with UNAVAILABLE (retryable) instead of launching.
        if (!scope.isActive) {
            responseObserver.onError(
                GrpcStatus.UNAVAILABLE
                    .withDescription("server shutting down; retry")
                    .asRuntimeException(),
            )
            return
        }

        // Callback → suspend bridge: launch on the per-call request scope, complete the observer inside.
        // NEVER runBlocking/Thread.sleep here (Pitfall 2 — blocking a grpc-netty event-loop thread
        // stalls the transport). SpanWriter.send is suspend; this is the only legal way to await it.
        scope.launch {
            // WR-01: the try wraps ONLY decode/send so a throw from a terminal observer call
            // (onNext/onCompleted on an already-cancelled call) can never re-enter onError on a
            // terminated observer (grpc-java's "call already closed" IllegalStateException). The
            // happy-path terminal calls live OUTSIDE the try.
            val response =
                try {
                    val result = decoder.decode(request)
                    // INGEST-03: accepted spans flow through the batching pipeline only — no repo call.
                    result.accepted.forEach { writer.send(it.span, it.context, it.spanName, it.serviceName) }
                    OtlpResponses.buildResponse(result)
                } catch (e: CancellationException) {
                    // WR-02: cooperative cancellation (scope cancelled mid-send) is NOT an internal
                    // fault — rethrow so structured concurrency stays intact, never map it to INTERNAL.
                    throw e
                } catch (e: Exception) {
                    // A3: an undecodable body is already rejected by gRPC's marshaller before export(),
                    // so reaching here is an internal fault, not a client error → INTERNAL.
                    responseObserver.onError(
                        GrpcStatus.INTERNAL.withDescription(e.message).asRuntimeException(),
                    )
                    return@launch
                }
            responseObserver.onNext(response)
            responseObserver.onCompleted()
        }
    }
}
