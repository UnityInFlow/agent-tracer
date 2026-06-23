package io.github.unityinflow.agenttracer.ingest

import io.github.unityinflow.agentsemconv.GenAiAttributes
import io.github.unityinflow.agenttracer.convention.SpanFixtures
import io.github.unityinflow.agenttracer.storage.AgentSpanRowMapper
import io.github.unityinflow.agenttracer.storage.SpanFixtures.DEFAULT_SPAN_ID
import io.github.unityinflow.agenttracer.storage.SpanFixtures.DEFAULT_TRACE_ID
import io.github.unityinflow.agenttracer.storage.SpanRepository
import io.github.unityinflow.agenttracer.storage.SpanRow
import io.github.unityinflow.agenttracer.storage.pipeline.BackpressureSignal
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process status-code conformance test for [Route.otlpTraces] via `ktor-server-test-host` — no
 * socket, no Docker. Proves the SC3 HTTP contract end-to-end: 200 (full), 200 (partial_success),
 * 400 (undecodable body), 429 + `Retry-After` (saturation fail-fast, body NOT decoded), and 413
 * (oversized body rejected on Content-Length before `call.receive`).
 *
 * **Saturation mechanism (the ONE test-double for this plan):** a real [BackpressureSignal] flipped
 * via its module-visible `internal set(true)` (these tests share the Gradle module). `set` mutates a
 * `MutableStateFlow`, so this is fully `val`-free per the CLAUDE.md `var` ban — no var-based stub.
 *
 * The writer is a real [SpanWriter] backed by a MockK fake [SpanRepository] capturing flushed rows
 * into a counter (`batchSize = 1` so each accepted span flushes promptly; `drainAndClose` flushes
 * the tail before any count assertion). The 429/400/413 paths assert ZERO rows reached the writer,
 * proving the fail-fast / reject-before-decode ordering, not merely the status code.
 *
 * Every `@Test` uses a block body (not an expression-body `= matcher`) so none is silently skipped
 * (Pitfall 5); the suite asserts 0 skipped at the phase gate.
 */
class OtlpHttpRoutesTest {
    private val protobuf = ContentType("application", "x-protobuf")

    /** Holds the per-test wiring: a real writer over a counting fake repo, plus its scope. */
    private class Harness {
        val flushedSpanCount = AtomicInteger(0)
        val signal = BackpressureSignal()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        }

        /** Flushes the in-flight tail so the flushed-span count is stable, then stops the writer. */
        suspend fun drain() = writer.drainAndClose()
    }

    /** A receiver config with a deliberately tiny body cap for the 413 case and a known Retry-After. */
    private fun config(httpMaxBodyBytes: Int = 32 * 1024 * 1024) =
        ReceiverConfig(
            httpPort = ReceiverConfig.DEFAULT_HTTP_PORT,
            grpcPort = ReceiverConfig.DEFAULT_GRPC_PORT,
            grpcMaxInboundBytes = ReceiverConfig.DEFAULT_GRPC_MAX_INBOUND_BYTES,
            httpMaxBodyBytes = httpMaxBodyBytes,
            retryAfterSeconds = 7,
        )

    /** A well-formed single-span request whose ids match the Phase-2 hex constants (decodes clean). */
    private fun validRequestBytes(): ByteArray {
        val span =
            OtlpRequestFixtures.spanProto(
                traceIdHex = DEFAULT_TRACE_ID,
                spanIdHex = DEFAULT_SPAN_ID,
                attrs = listOf(SpanFixtures.stringAttr(GenAiAttributes.AGENT_NAME, "planner")),
            )
        return OtlpRequestFixtures.exportRequest(OtlpRequestFixtures.resourceSpans(null, listOf(span))).toByteArray()
    }

    /** A request with one valid span + one empty-trace-id span → the decoder rejects exactly one. */
    private fun oneValidOneRejectedRequestBytes(): ByteArray {
        val good = OtlpRequestFixtures.spanProto(traceIdHex = DEFAULT_TRACE_ID, spanIdHex = DEFAULT_SPAN_ID)
        val bad = OtlpRequestFixtures.spanProto(traceIdHex = "", spanIdHex = DEFAULT_SPAN_ID)
        return OtlpRequestFixtures
            .exportRequest(OtlpRequestFixtures.resourceSpans(null, listOf(good, bad)))
            .toByteArray()
    }

    @Test
    fun wellFormedRequestReturns200WithNoPartialSuccessAndReachesWriter() {
        val h = Harness()
        testApplication {
            application { routing { otlpTraces(OtlpDecoder(), h.writer, h.signal, config()) } }
            val response =
                client.post("/v1/traces") {
                    contentType(protobuf)
                    setBody(validRequestBytes())
                }
            response.status shouldBe HttpStatusCode.OK
            val body = ExportTraceServiceResponse.parseFrom(response.readRawBytes())
            body.hasPartialSuccess() shouldBe false
            h.drain()
            h.flushedSpanCount.get() shouldBe 1
        }
    }

    @Test
    fun partialRejectionReturns200WithRejectedSpansSet() {
        val h = Harness()
        testApplication {
            application { routing { otlpTraces(OtlpDecoder(), h.writer, h.signal, config()) } }
            val response =
                client.post("/v1/traces") {
                    contentType(protobuf)
                    setBody(oneValidOneRejectedRequestBytes())
                }
            response.status shouldBe HttpStatusCode.OK
            val body = ExportTraceServiceResponse.parseFrom(response.readRawBytes())
            body.hasPartialSuccess() shouldBe true
            body.partialSuccess.rejectedSpans shouldBe 1L
            h.drain()
            // Only the one valid span reached the writer; the empty-id span was rejected pre-send.
            h.flushedSpanCount.get() shouldBe 1
        }
    }

    @Test
    fun undecodableBodyReturns400AndWriterReceivesNothing() {
        val h = Harness()
        testApplication {
            application { routing { otlpTraces(OtlpDecoder(), h.writer, h.signal, config()) } }
            val response =
                client.post("/v1/traces") {
                    contentType(protobuf)
                    setBody(byteArrayOf(0x42, 0x13, 0x37, 0x00, 0x7f)) // not valid protobuf
                }
            response.status shouldBe HttpStatusCode.BadRequest
            h.drain()
            h.flushedSpanCount.get() shouldBe 0
        }
    }

    @Test
    fun saturatedSignalReturns429WithRetryAfterAndDoesNotDecodeBody() {
        val h = Harness()
        h.signal.set(true) // module-visible internal setter; flips the real signal var-free
        testApplication {
            application { routing { otlpTraces(OtlpDecoder(), h.writer, h.signal, config()) } }
            val response =
                client.post("/v1/traces") {
                    contentType(protobuf)
                    setBody(validRequestBytes()) // a body that WOULD decode if read
                }
            response.status shouldBe HttpStatusCode.TooManyRequests
            response.headers[HttpHeaders.RetryAfter] shouldBe "7"
            h.drain()
            // Fail-fast proof: the would-be-valid span was NEVER decoded/sent under saturation.
            h.flushedSpanCount.get() shouldBe 0
        }
    }

    @Test
    fun oversizedBodyReturns413AndWriterReceivesNothing() {
        val h = Harness()
        testApplication {
            // Tiny cap so a normal request body trips the Content-Length guard before call.receive.
            application { routing { otlpTraces(OtlpDecoder(), h.writer, h.signal, config(httpMaxBodyBytes = 8)) } }
            val response =
                client.post("/v1/traces") {
                    contentType(protobuf)
                    setBody(validRequestBytes())
                }
            response.status shouldBe HttpStatusCode.PayloadTooLarge
            h.drain()
            h.flushedSpanCount.get() shouldBe 0
        }
    }

    /**
     * WR-03: a chunked / Content-Length-omitted body that exceeds the cap must STILL be rejected with
     * 413 — the Content-Length fast-path can't see it, so the read-time cap is what catches it. Sending
     * the body as a [ByteReadChannel] makes the client stream it WITHOUT a Content-Length header.
     */
    @Test
    fun chunkedOversizedBodyReturns413AndWriterReceivesNothing() {
        val h = Harness()
        testApplication {
            // Tiny cap; the over-cap body is streamed with NO Content-Length (the fast-path is blind to it).
            application { routing { otlpTraces(OtlpDecoder(), h.writer, h.signal, config(httpMaxBodyBytes = 8)) } }
            val response =
                client.post("/v1/traces") {
                    contentType(protobuf)
                    setBody(ByteReadChannel(validRequestBytes()))
                }
            response.status shouldBe HttpStatusCode.PayloadTooLarge
            h.drain()
            h.flushedSpanCount.get() shouldBe 0
        }
    }

    /**
     * WR-03 (complement): a chunked / Content-Length-omitted body UNDER the cap must still be accepted
     * and decoded — proving the read-time cap doesn't break the streaming happy path.
     */
    @Test
    fun chunkedUnderCapBodyIsAcceptedAndReachesWriter() {
        val h = Harness()
        testApplication {
            application { routing { otlpTraces(OtlpDecoder(), h.writer, h.signal, config()) } }
            val response =
                client.post("/v1/traces") {
                    contentType(protobuf)
                    setBody(ByteReadChannel(validRequestBytes()))
                }
            response.status shouldBe HttpStatusCode.OK
            val body = ExportTraceServiceResponse.parseFrom(response.readRawBytes())
            body.hasPartialSuccess() shouldBe false
            h.drain()
            h.flushedSpanCount.get() shouldBe 1
        }
    }
}
