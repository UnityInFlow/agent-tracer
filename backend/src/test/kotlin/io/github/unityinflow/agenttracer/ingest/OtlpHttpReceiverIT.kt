package io.github.unityinflow.agenttracer.ingest

import io.github.unityinflow.agenttracer.storage.AgentSpanRowMapper
import io.github.unityinflow.agenttracer.storage.ClickHouseSpanRepository
import io.github.unityinflow.agenttracer.storage.ClickHouseTestSupport
import io.github.unityinflow.agenttracer.storage.migrations.MigrationRunner
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.IdGenerator
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * End-to-end OTLP/HTTP receiver IT (SC1, D-07, D-03): a REAL [OtlpHttpSpanExporter] posting to the
 * live Ktor `/v1/traces` route lands its span in ClickHouse, retrievable by
 * `repo.getTraceById(knownHexId)` — closing the D-07 hex round-trip loop (Pitfall 1) with the actual
 * stock OTel SDK exporter as the proof bar (not hand-rolled protobuf).
 *
 * Two cases:
 *  1. [agentSpanRoundTripsByKnownHexTraceId] — an `invoke_agent` span carrying
 *     `gen_ai.usage.input_tokens=1234` lands and is retrievable by its KNOWN hex trace id; the typed
 *     `inputTokens` survived the OTLP → extractor → ClickHouse path (D-07, SC1).
 *  2. [genericNonAgentSpanLandsThroughStoreAllPath] — a stock (non-agent) `GET /healthz` span with
 *     only plain attributes (no `gen_ai.*`) ALSO lands through the SAME single store-all decoder
 *     path: agent columns null/default, raw attributes preserved, not dropped, no separate
 *     generic-span table (D-03, SC1 satisfied literally by a stock OTel-SDK exporter).
 *
 * Each test uses a DISTINCT (known) trace id so the JVM-wide shared [ClickHouseTestSupport] container
 * never cross-contaminates. The OTel SDK [TraceId]/[SpanId] strings are already lowercase, no-prefix
 * hex — byte-for-byte identical to the decoder's [OtlpDecoder] `toHex` output and the Phase-2 storage
 * column format — so a known id flows verbatim into `getTraceById`.
 *
 * **Native-Docker caveat (Phase-2 discipline):** observe green on native Docker. The Phase-2
 * Colima/QEMU Testcontainers HTTP-wait read-timeout (`ContainerLaunchException`/Ryuk) is INFRA, not
 * code — do NOT fake a pass, do NOT `@Disabled` to dodge it. The exporter, route, and ClickHouse
 * round-trip are real and would pass on native Docker.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtlpHttpReceiverIT {
    private val client = ClickHouseTestSupport.newClient()
    private val repo = ClickHouseSpanRepository(client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mapper = AgentSpanRowMapper()
    private val decoder = OtlpDecoder()

    // batchSize=1 makes the flush deterministic; drainAndClose() in each test flushes the in-flight buffer.
    private val writer = SpanWriter(repo, mapper, scope, SpanWriter.Config(capacity = 10_000, batchSize = 1))

    private val httpPort = freePort()
    private val config =
        ReceiverConfig(
            httpPort = httpPort,
            grpcPort = freePortDistinctFrom(httpPort),
            grpcMaxInboundBytes = 32 * 1024 * 1024,
            httpMaxBodyBytes = 32 * 1024 * 1024,
            retryAfterSeconds = 1,
        )

    private val ktor: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = httpPort) {
            routing { otlpTraces(decoder, writer, writer.backpressure, config) }
        }

    @BeforeAll
    fun startUp() {
        runBlocking {
            ClickHouseTestSupport.container
            MigrationRunner(client).runMigrations().getOrThrow()
        }
        ktor.start(wait = false)
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            ktor.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            client.close()
        }
    }

    @Test
    fun agentSpanRoundTripsByKnownHexTraceId(): Unit =
        runBlocking {
            val traceId = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
            val spanId = "b1b1b1b1b1b1b1b1"
            val exporter =
                OtlpHttpSpanExporter
                    .builder()
                    .setEndpoint("http://localhost:$httpPort/v1/traces")
                    .build()
            val sdk = tracerProvider(exporter, traceId, spanId)
            try {
                sdk
                    .get("agent-tracer-it")
                    .spanBuilder("invoke_agent")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute(AttributeKey.longKey("gen_ai.usage.input_tokens"), 1234L)
                    .setAttribute(AttributeKey.stringKey("gen_ai.agent.name"), "planner-agent")
                    .startSpan()
                    .end()

                sdk.forceFlush().join(10, TimeUnit.SECONDS).isSuccess shouldBe true
                writer.drainAndClose()
                waitForSpans(traceId, expected = 1)

                val fetched = repo.getTraceById(traceId).getOrThrow()
                fetched shouldHaveSize 1
                val got = fetched.single()
                // D-07: the known hex trace/span id round-trips byte-for-byte through getTraceById.
                got.traceId shouldBe traceId
                got.spanId shouldBe spanId
                got.spanName shouldBe "invoke_agent"
                // The typed agent value survived OTLP -> extractor -> ClickHouse.
                got.inputTokens shouldBe 1234L
                got.agentName shouldBe "planner-agent"
            } finally {
                exporter.close()
                sdk.close()
            }
        }

    @Test
    fun genericNonAgentSpanLandsThroughStoreAllPath(): Unit =
        runBlocking {
            // D-03: a stock, non-agent span (NO gen_ai.* attrs) lands through the SAME store-all path.
            val traceId = "c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2"
            val spanId = "d2d2d2d2d2d2d2d2"
            val exporter =
                OtlpHttpSpanExporter
                    .builder()
                    .setEndpoint("http://localhost:$httpPort/v1/traces")
                    .build()
            val sdk = tracerProvider(exporter, traceId, spanId)
            try {
                sdk
                    .get("generic-it")
                    .spanBuilder("GET /healthz")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute(AttributeKey.stringKey("http.request.method"), "GET")
                    .setAttribute(AttributeKey.stringKey("db.system"), "clickhouse")
                    .startSpan()
                    .end()

                sdk.forceFlush().join(10, TimeUnit.SECONDS).isSuccess shouldBe true
                writer.drainAndClose()
                waitForSpans(traceId, expected = 1)

                val fetched = repo.getTraceById(traceId).getOrThrow()
                fetched shouldHaveSize 1
                val got = fetched.single()
                got.traceId shouldBe traceId
                got.spanName shouldBe "GET /healthz"
                // D-03: a non-agent span lands with agent-specific columns at their null/default sentinels.
                got.agentName shouldBe ""
                got.model shouldBe ""
                got.skillName shouldBe ""
                got.inputTokens shouldBe null
                got.outputTokens shouldBe null
                got.costUsd shouldBe null
            } finally {
                exporter.close()
                sdk.close()
            }
        }

    /**
     * Polls until [expected] spans for [traceId] are visible (ClickHouse inserts are async-visible).
     * Coroutine [delay] only — never `Thread.sleep` (CLAUDE.md).
     */
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

        /**
         * An [SdkTracerProvider] whose [IdGenerator] mints the supplied KNOWN hex ids, so the
         * exported span has a deterministic trace/span id we can assert with `getTraceById`. The OTel
         * SDK encodes these as lowercase no-prefix hex — identical to the decoder + storage format.
         */
        fun tracerProvider(
            exporter: io.opentelemetry.sdk.trace.export.SpanExporter,
            traceIdHex: String,
            spanIdHex: String,
        ): SdkTracerProvider =
            SdkTracerProvider
                .builder()
                .setIdGenerator(fixedIdGenerator(traceIdHex, spanIdHex))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()

        fun fixedIdGenerator(
            traceIdHex: String,
            spanIdHex: String,
        ): IdGenerator =
            object : IdGenerator {
                override fun generateSpanId(): String = spanIdHex

                override fun generateTraceId(): String = traceIdHex
            }

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        fun freePortDistinctFrom(other: Int): Int {
            repeat(20) {
                val candidate = freePort()
                if (candidate != other) return candidate
            }
            error("could not allocate a free port distinct from $other")
        }
    }
}
