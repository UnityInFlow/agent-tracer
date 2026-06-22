package io.github.unityinflow.agenttracer.app

import io.github.unityinflow.agenttracer.ingest.ReceiverConfig
import io.github.unityinflow.agenttracer.storage.ClickHouseConfig
import io.github.unityinflow.agenttracer.storage.ClickHouseTestSupport
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.IdGenerator
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Proves the [bootReceivers]/[shutdown] D-04 lifecycle end-to-end against a real ClickHouse
 * (Testcontainers, shared [ClickHouseTestSupport] container) — without spawning a separate JVM
 * process (it reuses the testable boot/drain helpers from `TracerApplication.kt`):
 *
 *  1. [schemaIsPresentAfterBootBeforeServing] — boot runs migrations to COMPLETION before the servers
 *     accept requests: a `SELECT … FROM spans` succeeds immediately post-boot (no first-request
 *     schema error). Proves D-04 step 2 / Pitfall 7 (migrate-then-serve).
 *  2. [drainOnShutdownLosesNoInFlightSpan] — drive N spans through the started HTTP receiver, run the
 *     shutdown sequence (close ports → `drainAndClose()` → `client.close()`), then assert all N
 *     landed in ClickHouse (the in-flight buffer was flushed, not dropped) AND the HTTP port is
 *     closed afterwards (a fresh connection to it is refused) — proving the ports-first drain order
 *     (D-04 step 5, T-03-14).
 *
 * Each test boots a fresh receiver set on ephemeral ports with a DISTINCT trace id so the shared
 * container never cross-contaminates. Coroutine [delay] only — never `Thread.sleep`.
 *
 * Native-Docker caveat: observe green on native Docker; the Phase-2 Colima/QEMU Testcontainers
 * Ryuk/HTTP-wait read-timeout is infra, not code — do NOT fake a pass, do NOT `@Disabled` to dodge it.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracerApplicationIT {
    private val chConfig: ClickHouseConfig by lazy {
        val container = ClickHouseTestSupport.container
        ClickHouseConfig(
            url = "http://${container.host}:${container.firstMappedPort}/",
            database = "default",
            user = container.username,
            password = container.password,
        )
    }

    @BeforeAll
    fun ensureContainer() {
        ClickHouseTestSupport.container
    }

    @Test
    fun schemaIsPresentAfterBootBeforeServing(): Unit =
        runBlocking {
            val receivers = bootReceivers(chConfig, freeReceiverConfig())
            try {
                // Migrate-then-serve (D-04 step 2): the spans table exists the instant boot returns —
                // a query against it succeeds with no first-request schema error (Pitfall 7).
                val count =
                    receivers.client
                        .queryAll("SELECT count() AS c FROM spans")
                        .single()
                        .getLong("c")
                count shouldBeGreaterThanOrEqual 0L
            } finally {
                receivers.shutdown()
            }
        }

    @Test
    fun drainOnShutdownLosesNoInFlightSpan(): Unit =
        runBlocking {
            val traceId = "d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4"
            val config = freeReceiverConfig()
            val receivers = bootReceivers(chConfig, config)
            val httpPort = config.httpPort
            val n = 5

            val exporter =
                OtlpHttpSpanExporter
                    .builder()
                    .setEndpoint("http://localhost:$httpPort/v1/traces")
                    .build()
            val sdk = tracerProvider(exporter, traceId)
            try {
                repeat(n) { i ->
                    sdk
                        .get("drain-it")
                        .spanBuilder("step-$i")
                        .setAttribute(AttributeKey.longKey("unityinflow.plan.step"), (i + 1).toLong())
                        .startSpan()
                        .end()
                }
                sdk.forceFlush().join(10, TimeUnit.SECONDS).isSuccess shouldBe true
            } finally {
                exporter.close()
                sdk.close()
            }

            // Shutdown sequence: ports close FIRST, then the in-flight buffer drains, then client closes.
            receivers.shutdown()

            // Re-open a client (the booted one was closed by shutdown) to assert the drained spans landed.
            val verifyClient = ClickHouseTestSupport.newClient()
            try {
                val landed = countSpans(verifyClient, traceId)
                // No span lost on shutdown: every emitted span was flushed by drainAndClose (D-04 step 5).
                landed shouldBe n.toLong()
            } finally {
                verifyClient.close()
            }

            // Ports-first proof: after shutdown the HTTP port is closed — a fresh connection is refused.
            httpPortIsClosed(httpPort) shouldBe true
        }

    private suspend fun countSpans(
        client: com.clickhouse.client.api.Client,
        traceId: String,
    ): Long {
        repeat(POLL_ATTEMPTS) {
            val count =
                client
                    .queryAll(
                        "SELECT count() AS c FROM spans WHERE TraceId = {tid:String}",
                        mapOf("tid" to traceId),
                    ).single()
                    .getLong("c")
            if (count > 0L) return count
            delay(POLL_DELAY_MS)
        }
        return 0L
    }

    private companion object {
        const val POLL_ATTEMPTS = 50
        const val POLL_DELAY_MS = 100L

        fun freeReceiverConfig(): ReceiverConfig {
            val http = freePort()
            return ReceiverConfig(
                httpPort = http,
                grpcPort = freePortDistinctFrom(http),
                grpcMaxInboundBytes = 32 * 1024 * 1024,
                httpMaxBodyBytes = 32 * 1024 * 1024,
                retryAfterSeconds = 1,
            )
        }

        fun tracerProvider(
            exporter: SpanExporter,
            traceIdHex: String,
        ): SdkTracerProvider =
            SdkTracerProvider
                .builder()
                .setIdGenerator(
                    object : IdGenerator {
                        // Distinct span id per call so N spans share the trace id but never collide.
                        private val counter =
                            java.util.concurrent.atomic
                                .AtomicLong(0)

                        override fun generateSpanId(): String = "%016x".format(counter.incrementAndGet())

                        override fun generateTraceId(): String = traceIdHex
                    },
                ).addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()

        fun httpPortIsClosed(port: Int): Boolean =
            try {
                java.net.Socket("localhost", port).use { false }
            } catch (e: java.io.IOException) {
                // Connection refused / reset — the port is closed (the expected post-shutdown state).
                true
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
