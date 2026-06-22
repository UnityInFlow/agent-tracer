package io.github.unityinflow.agenttracer.ingest

import io.github.unityinflow.agenttracer.storage.AgentSpanRowMapper
import io.github.unityinflow.agenttracer.storage.ClickHouseSpanRepository
import io.github.unityinflow.agenttracer.storage.ClickHouseTestSupport
import io.github.unityinflow.agenttracer.storage.migrations.MigrationRunner
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.trace.IdGenerator
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * End-to-end OTLP/gRPC receiver IT (SC2): a REAL [OtlpGrpcSpanExporter] sends a >4 MB prompt-heavy
 * span to the live standalone `io.grpc.Server` on :grpcPort, and it lands in ClickHouse — proving the
 * raised `maxInboundMessageSize` (32 MiB) accepts spans over gRPC's 4 MiB default cap, and that the
 * oversized payload is TRUNCATED per D-02/D-13 (256 KB cap) downstream, NOT rejected.
 *
 * **Plaintext transport invariant (revised Task 2):** the test gRPC server is a PLAINTEXT
 * [NettyServerBuilder] — NO `useTransportSecurity`/TLS is configured. OTLP gRPC plaintext is addressed
 * as `http://host:port` (NEVER `grpc://` or `https://`). The exporter endpoint is therefore
 * `http://localhost:$grpcPort`, and `forceFlush().join(10, SECONDS)` is asserted to return
 * `isSuccess` within the HARD bound — so a plaintext/TLS mismatch or wrong scheme FAILS FAST here
 * instead of hanging.
 *
 * Native-Docker caveat: observe green on native Docker; the Phase-2 Colima/QEMU Testcontainers
 * HTTP-wait read-timeout is infra, not code — do NOT fake a pass, do NOT `@Disabled` to dodge it.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtlpGrpcReceiverIT {
    private val client = ClickHouseTestSupport.newClient()
    private val repo = ClickHouseSpanRepository(client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mapper = AgentSpanRowMapper()
    private val decoder = OtlpDecoder()
    private val writer = SpanWriter(repo, mapper, scope, SpanWriter.Config(capacity = 10_000, batchSize = 1))

    private val grpcPort = freePort()

    private val grpc: Server =
        NettyServerBuilder
            .forPort(grpcPort)
            // SC2: raise the inbound cap above gRPC's 4 MiB default so the 6 MB span is accepted.
            .maxInboundMessageSize(32 * 1024 * 1024)
            // PLAINTEXT: no useTransportSecurity() — the exporter must connect over http:// (not grpc://).
            .addService(OtlpGrpcService(decoder, writer, writer.backpressure, scope))
            .build()

    @BeforeAll
    fun startUp() {
        runBlocking {
            ClickHouseTestSupport.container
            MigrationRunner(client).runMigrations().getOrThrow()
        }
        grpc.start()
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            grpc.shutdown().awaitTermination(5, TimeUnit.SECONDS)
            scope.coroutineContext[Job]?.cancel()
            client.close()
        }
    }

    @Test
    fun oversizedGrpcSpanLandsTruncatedNotRejected(): Unit =
        runBlocking {
            val traceId = "e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3"
            val spanId = "f3f3f3f3f3f3f3f3"
            // 6 MB > gRPC's 4 MiB default cap (proves the raised maxInboundMessageSize) and
            // > the 256 KB payload cap (so it must be truncated downstream, not dropped).
            val hugePrompt = "x".repeat(6 * 1024 * 1024)

            val exporter =
                OtlpGrpcSpanExporter
                    .builder()
                    // PLAINTEXT gRPC is addressed as http:// — never grpc:// / https:// (no TLS on the server).
                    .setEndpoint("http://localhost:$grpcPort")
                    .setTimeout(30, TimeUnit.SECONDS)
                    .build()
            val sdk = tracerProvider(exporter, traceId, spanId)
            try {
                sdk
                    .get("grpc-it")
                    .spanBuilder("invoke_agent")
                    .setAttribute(AttributeKey.stringKey("gen_ai.input.messages"), hugePrompt)
                    .setAttribute(AttributeKey.stringKey("gen_ai.agent.name"), "planner-agent")
                    .startSpan()
                    .end()

                // HARD-BOUNDED flush: a plaintext/TLS or scheme mismatch fails fast here, never hangs.
                sdk.forceFlush().join(10, TimeUnit.SECONDS).isSuccess shouldBe true
                writer.drainAndClose()
                waitForSpans(traceId, expected = 1)

                val fetched = repo.getTraceById(traceId).getOrThrow()
                fetched shouldHaveSize 1
                val got = fetched.single()
                got.traceId shouldBe traceId
                // The 6 MB span PERSISTED (accepted, not rejected) and is flagged truncated (D-02/D-13).
                got.payloadTruncated shouldBe true
                got.payloadOriginalSizeBytes.shouldNotBeNull() shouldBe (6L * 1024 * 1024)

                // The capped body landed in payloads at exactly the 256 KB cap, with truncation metadata.
                val payload = repo.getPayload(traceId, spanId).getOrThrow().shouldNotBeNull()
                payload.payloadTruncated shouldBe true
                payload.inputPayload.length shouldBe AgentSpanRowMapper.PAYLOAD_CAP_BYTES
                payload.payloadOriginalSizeBytes.shouldNotBeNull() shouldBe (6L * 1024 * 1024)
            } finally {
                exporter.close()
                sdk.close()
            }
        }

    private suspend fun waitForSpans(
        traceId: String,
        expected: Int,
    ) {
        repeat(POLL_ATTEMPTS) {
            val count =
                client
                    .queryAll(
                        "SELECT count() AS c FROM spans WHERE TraceId = {tid:String}",
                        mapOf("tid" to traceId),
                    ).single()
                    .getLong("c")
            if (count >= expected) return
            delay(POLL_DELAY_MS)
        }
    }

    private companion object {
        const val POLL_ATTEMPTS = 50
        const val POLL_DELAY_MS = 100L

        fun tracerProvider(
            exporter: SpanExporter,
            traceIdHex: String,
            spanIdHex: String,
        ): SdkTracerProvider =
            SdkTracerProvider
                .builder()
                .setIdGenerator(
                    object : IdGenerator {
                        override fun generateSpanId(): String = spanIdHex

                        override fun generateTraceId(): String = traceIdHex
                    },
                ).addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()

        fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
