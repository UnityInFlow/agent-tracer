package io.github.unityinflow.agenttracer.ingest

import io.github.unityinflow.agenttracer.storage.AgentSpanRowMapper
import io.github.unityinflow.agenttracer.storage.SpanFixtures.DEFAULT_SPAN_ID
import io.github.unityinflow.agenttracer.storage.SpanFixtures.DEFAULT_TRACE_ID
import io.github.unityinflow.agenttracer.storage.SpanRepository
import io.github.unityinflow.agenttracer.storage.SpanRow
import io.github.unityinflow.agenttracer.storage.pipeline.BackpressureSignal
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process gRPC contract test for [OtlpGrpcService] — drives the service over an in-memory
 * `io.grpc` channel ([InProcessServerBuilder]/[InProcessChannelBuilder]); no socket, no Docker.
 * Proves the export contract end-to-end: a well-formed export returns `partial_success` UNSET and
 * the span reaches the writer; a valid+empty-id pair returns `partial_success.rejected_spans == 1`
 * (D-02); and a saturated [BackpressureSignal] makes the call fail with a [StatusRuntimeException]
 * whose code is `RESOURCE_EXHAUSTED` (D-01) WHILE leaving the writer empty (fail-fast, no decode).
 *
 * **Saturation mechanism (the ONE pinned test-double):** a real [BackpressureSignal] flipped via its
 * module-visible `internal set(true)` (these tests share the Gradle module). `isSaturated` is a
 * non-open `val` so no subclass/supplier-fake can override it; `set` mutates a `MutableStateFlow`, so
 * this is fully `var`-free per the CLAUDE.md ban.
 *
 * The writer is a real [SpanWriter] over a MockK fake [SpanRepository] counting flushed rows
 * (`batchSize = 1` so each accepted span flushes promptly; `drainAndClose` flushes the tail before
 * any count assertion). The success path also proves the coroutine bridge indirectly: the blocking
 * stub call only returns once the `StreamObserver` was completed from INSIDE the launched coroutine —
 * i.e. without blocking the gRPC callback thread.
 *
 * A real >4 MB message-size proof (SC2) is the end-to-end IT in 03-04 (it needs the standalone Netty
 * server with the raised `maxInboundMessageSize` cap); this unit test focuses on the export contract
 * + saturation status over the in-process channel.
 *
 * Every `@Test` uses a block body (not an expression-body `= matcher`) so none is silently skipped
 * (Pitfall 5).
 */
class OtlpGrpcServiceTest {
    /** Per-test wiring: a real writer over a counting fake repo, the in-process server + channel. */
    private class Harness {
        val flushedSpanCount = AtomicInteger(0)
        val signal = BackpressureSignal()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val serverName = "otlp-grpc-test-${UUID.randomUUID()}"
        private val server: Server
        private val channel: ManagedChannel
        val writer: SpanWriter

        init {
            val repo = mockk<SpanRepository>()
            val rows = slot<List<SpanRow>>()
            coEvery { repo.writeBatch(capture(rows), any()) } answers {
                flushedSpanCount.addAndGet(rows.captured.size)
                Result.success(Unit)
            }
            // batchSize = 1 → each accepted span flushes on the count trigger without a time advance.
            writer = SpanWriter(repo, AgentSpanRowMapper(), scope, SpanWriter.Config(capacity = 100, batchSize = 1))

            val service = OtlpGrpcService(OtlpDecoder(), writer, signal, scope)
            server =
                InProcessServerBuilder
                    .forName(serverName)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        }

        /** A future stub over the in-process channel (futures bridge cleanly to a blocking `.get()`). */
        fun blockingStub(): TraceServiceGrpc.TraceServiceBlockingStub = TraceServiceGrpc.newBlockingStub(channel)

        /** Flushes the in-flight tail so the flushed-span count is stable, then stops the writer. */
        suspend fun drain() = writer.drainAndClose()

        /** Tears down channel + server (no leaked in-process transports between tests). */
        fun shutdown() {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    private val harness = Harness()

    @AfterEach
    fun tearDown() {
        harness.shutdown()
    }

    /** A well-formed single-span request whose ids match the Phase-2 hex constants (decodes clean). */
    private fun validRequest() =
        OtlpRequestFixtures.exportRequest(
            OtlpRequestFixtures.resourceSpans(
                null,
                listOf(OtlpRequestFixtures.spanProto(traceIdHex = DEFAULT_TRACE_ID, spanIdHex = DEFAULT_SPAN_ID)),
            ),
        )

    /** One valid span + one empty-trace-id span → the decoder rejects exactly one (D-02). */
    private fun oneValidOneRejectedRequest() =
        OtlpRequestFixtures.exportRequest(
            OtlpRequestFixtures.resourceSpans(
                null,
                listOf(
                    OtlpRequestFixtures.spanProto(traceIdHex = DEFAULT_TRACE_ID, spanIdHex = DEFAULT_SPAN_ID),
                    OtlpRequestFixtures.spanProto(traceIdHex = "", spanIdHex = DEFAULT_SPAN_ID),
                ),
            ),
        )

    @Test
    fun wellFormedExportReturnsResponseWithNoPartialSuccessAndReachesWriter() {
        val response = harness.blockingStub().export(validRequest())
        response.hasPartialSuccess() shouldBe false
        runBlocking { harness.drain() }
        harness.flushedSpanCount.get() shouldBe 1
    }

    @Test
    fun partialRejectionReturnsRejectedSpansOne() {
        val response = harness.blockingStub().export(oneValidOneRejectedRequest())
        response.hasPartialSuccess() shouldBe true
        response.partialSuccess.rejectedSpans shouldBe 1L
        runBlocking { harness.drain() }
        // Only the one valid span reached the writer; the empty-id span was rejected pre-send.
        harness.flushedSpanCount.get() shouldBe 1
    }

    @Test
    fun saturatedSignalFailsWithResourceExhaustedAndDoesNotDecode() {
        harness.signal.set(true) // module-visible internal setter; flips the real signal var-free
        val thrown =
            shouldThrow<StatusRuntimeException> {
                harness.blockingStub().export(validRequest()) // a body that WOULD decode if read
            }
        thrown.status.code shouldBe Status.Code.RESOURCE_EXHAUSTED
        runBlocking { harness.drain() }
        // Fail-fast proof: the would-be-valid span was NEVER decoded/sent under saturation.
        harness.flushedSpanCount.get() shouldBe 0
    }
}
