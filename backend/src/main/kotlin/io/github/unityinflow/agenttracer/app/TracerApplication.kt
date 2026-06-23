package io.github.unityinflow.agenttracer.app

import com.clickhouse.client.api.Client
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.unityinflow.agenttracer.ingest.OtlpDecoder
import io.github.unityinflow.agenttracer.ingest.OtlpGrpcService
import io.github.unityinflow.agenttracer.ingest.ReceiverConfig
import io.github.unityinflow.agenttracer.ingest.otlpTraces
import io.github.unityinflow.agenttracer.storage.AgentSpanRowMapper
import io.github.unityinflow.agenttracer.storage.ClickHouseClientFactory
import io.github.unityinflow.agenttracer.storage.ClickHouseConfig
import io.github.unityinflow.agenttracer.storage.ClickHouseSpanRepository
import io.github.unityinflow.agenttracer.storage.migrations.MigrationRunner
import io.github.unityinflow.agenttracer.storage.pipeline.SpanWriter
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * The application bootstrap (D-05 manual DI): hosts BOTH OTLP receivers (:4318 Ktor/HTTP +
 * :4317 standalone `io.grpc.Server`), the single Phase-2 [SpanWriter] batching pipeline, and the
 * ClickHouse client in ONE JVM, tied together with the strict D-04 lifecycle:
 *
 *  1. **migrate → serve**: [MigrationRunner.runMigrations] runs to completion via `.getOrThrow()`
 *     BEFORE any port opens, so a Code-62/549 schema fault surfaces at boot (fail fast, Pitfall 7),
 *     never at first request time.
 *  2. **serve**: both transports share the SAME [OtlpDecoder], [SpanWriter], and
 *     [SpanWriter.backpressure] signal — saturation is global and consistent (T-03-12). The gRPC
 *     server sets `.maxInboundMessageSize(grpcMaxInboundBytes)` so prompt-heavy spans over gRPC's
 *     4 MiB default are accepted (SC2).
 *  3. **drain**: the shutdown hook closes the PORTS FIRST (so no new span is accepted), THEN joins
 *     the gRPC per-call request scope so every in-flight `export` coroutine has finished handing its
 *     spans to the writer (CR-01 quiescence barrier — a span can never reach a closed intake), THEN
 *     calls [SpanWriter.drainAndClose] to flush the in-flight buffer, THEN closes the [Client], THEN
 *     cancels the writer scope — no span lost on `SIGTERM` / `docker compose down` (D-04 drain order,
 *     T-03-14).
 *
 * Manual DI keeps wiring explicit and testable: [bootReceivers] returns a [RunningReceivers] handle
 * the IT exercises directly (boot/drain without spawning a process); [shutdown] runs the drain
 * sequence. No Gradle `application` plugin here — packaging is a Phase-4 concern.
 *
 * `val`-only, no `!!`, no `Thread.sleep` (coroutine `awaitTermination`/`join` everywhere).
 */
private const val GRPC_SHUTDOWN_TIMEOUT_SECONDS = 10L
private const val KTOR_GRACE_MILLIS = 1_000L
private const val KTOR_SHUTDOWN_TIMEOUT_MILLIS = 5_000L

/**
 * A booted set of receivers plus the resources whose lifecycle the bootstrap owns. The IT and
 * [main] both shut down through [shutdown]; the handle exposes the live [ports]/[writer]/[client]
 * so a test can drive spans through the started transports and then assert post-drain persistence.
 *
 * @property client the open ClickHouse client (closed late in [shutdown]).
 * @property writer the single batching pipeline both transports feed (drained AFTER the gRPC call
 *   scope quiesces, BEFORE the client closes).
 * @property repository the read seam tests use to assert spans landed (`getTraceById`).
 * @property grpc the started standalone gRPC server (:grpcPort).
 * @property ktor the started Ktor/Netty HTTP server (:httpPort).
 * @property writerScope the lifecycle [CoroutineScope] owning the [SpanWriter] consumer coroutine;
 *   cancelled LAST so the writer is never torn out from under an in-flight flush.
 * @property grpcCallScope the gRPC service's OWN per-call request [CoroutineScope], a child of
 *   [writerScope]. Its [Job] is joined in [shutdown] AFTER the ports close and BEFORE the writer is
 *   drained, so an accepted gRPC span can never hit a closed intake channel (CR-01/CR-02).
 * @property config the resolved receiver config (the bound ports live here).
 */
class RunningReceivers(
    val client: Client,
    val writer: SpanWriter,
    val repository: ClickHouseSpanRepository,
    val grpc: Server,
    val ktor: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>,
    val writerScope: CoroutineScope,
    val grpcCallScope: CoroutineScope,
    val config: ReceiverConfig,
)

/**
 * D-04 step 1–3 (migrate → serve), without the blocking await. Runs migrations to completion BEFORE
 * opening any port (fail fast via `.getOrThrow()`), then starts the single [SpanWriter] pipeline and
 * BOTH transports against it, returning a live [RunningReceivers] handle.
 *
 * The [ClickHouseClientFactory] sets the default database on the client, so the repository takes the
 * 1-arg ctor (PATTERNS correction — do NOT pass a database to the repo).
 */
suspend fun bootReceivers(
    chConfig: ClickHouseConfig,
    receiverConfig: ReceiverConfig,
    client: Client = ClickHouseClientFactory.create(chConfig),
): RunningReceivers {
    // D-04 step 2 (Pitfall 7): migrate to completion BEFORE any port opens — fail fast at boot.
    MigrationRunner(client).runMigrations().getOrThrow()

    val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repository = ClickHouseSpanRepository(client) // 1-arg ctor (factory set the default database).
    val writer = SpanWriter(repository, AgentSpanRowMapper(), writerScope)
    val decoder = OtlpDecoder()

    // CR-01/CR-02: the gRPC service gets its OWN per-call request scope — a child of the writer scope
    // (so it shares the lifecycle/dispatcher) but with a distinct Job we can join to quiescence at
    // shutdown BEFORE draining the writer. This is the ordering barrier that guarantees no in-flight
    // export coroutine calls writer.send after the intake channel is closed.
    val grpcCallScope =
        CoroutineScope(writerScope.coroutineContext + SupervisorJob(writerScope.coroutineContext.job))

    // Standalone io.grpc.Server (:4317) — Ktor cannot host gRPC. The raised inbound cap is SC2's enabler.
    val grpc =
        NettyServerBuilder
            .forPort(receiverConfig.grpcPort)
            .maxInboundMessageSize(receiverConfig.grpcMaxInboundBytes)
            .addService(OtlpGrpcService(decoder, writer, writer.backpressure, grpcCallScope))
            .build()
            .start()

    // Ktor/Netty (:4318) hosts the OTLP/HTTP route on the SAME decoder/writer/backpressure signal.
    val ktor =
        embeddedServer(Netty, port = receiverConfig.httpPort) {
            installOtlp(decoder, writer, receiverConfig)
        }.start(wait = false)

    logger.info { "Receivers up: HTTP :${receiverConfig.httpPort}, gRPC :${receiverConfig.grpcPort}" }
    return RunningReceivers(client, writer, repository, grpc, ktor, writerScope, grpcCallScope, receiverConfig)
}

/** Installs the OTLP/HTTP route on this Ktor [Application], sharing the pipeline's backpressure signal. */
fun Application.installOtlp(
    decoder: OtlpDecoder,
    writer: SpanWriter,
    config: ReceiverConfig,
) {
    routing {
        otlpTraces(decoder, writer, writer.backpressure, config)
    }
}

/**
 * D-04 drain order (Pitfall 7, T-03-14, CR-01/CR-02): close BOTH ports first (so no new span is
 * accepted), THEN join the gRPC per-call request scope so every in-flight `export` coroutine has
 * finished its `writer.send` (the quiescence barrier — a span can never reach a closed intake), THEN
 * flush the in-flight buffer via [SpanWriter.drainAndClose], THEN close the client, THEN cancel the
 * writer scope. Coroutine `awaitTermination`/`join` only — never `Thread.sleep`. Idempotent enough to
 * run once from the shutdown hook; re-running is harmless (closed servers/channels no-op).
 */
suspend fun RunningReceivers.shutdown() {
    // 1. Ports first — refuse new spans before draining. Once the gRPC transport has terminated, no
    //    new export() is invoked; in-flight RPCs have returned but their launched coroutines may not.
    grpc.shutdown().awaitTermination(GRPC_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    ktor.stop(gracePeriodMillis = KTOR_GRACE_MILLIS, timeoutMillis = KTOR_SHUTDOWN_TIMEOUT_MILLIS)
    // 2. Quiescence barrier (CR-01/CR-02): join the gRPC call scope's children so every launched
    //    export coroutine has finished handing its spans to the writer BEFORE the intake closes.
    val grpcCallJob: Job = grpcCallScope.coroutineContext.job
    grpcCallJob.children.forEach { it.join() }
    // 3. Drain the in-flight buffer — now safe, no more writer.send can happen. No span lost.
    writer.drainAndClose()
    // 4. Close the client, then cancel the writer scope (writer already joined by drainAndClose).
    client.close()
    grpcCallScope.cancel()
    writerScope.cancel()
    logger.info { "Receivers drained and shut down cleanly" }
}

/**
 * `fun main()` — the migrate→serve→drain bootstrap. Wraps the lifecycle in [runBlocking] because the
 * migrations and drain are `suspend`.
 *
 * **WR-05 — no startup-window span loss:** the shutdown hook is registered BEFORE [bootReceivers]
 * runs, against an [AtomicReference] handle that boot populates the instant the receivers are live.
 * A `SIGTERM` arriving DURING boot finds a `null` handle and is a clean no-op (no ports were open
 * yet, nothing to drain); a `SIGTERM` arriving any time AFTER boot returns finds the populated handle
 * and runs the full ports→quiesce→drain→client sequence. There is no window between "ports open /
 * writer started" and "hook registered" in which a signal would leak the listeners or lose buffered
 * spans. The hook's `getAndSet(null)` makes the drain run at most once (a second invocation sees a
 * `null` handle and no-ops).
 */
fun main(): Unit =
    runBlocking {
        val handle = AtomicReference<RunningReceivers?>(null)

        // Register the drain hook FIRST, against the (initially empty) handle — so a SIGTERM during
        // boot can never miss it. The hook drains only once boot has populated the handle.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                val live = handle.getAndSet(null)
                if (live != null) {
                    runBlocking { live.shutdown() }
                }
            },
        )

        val receivers =
            bootReceivers(
                chConfig = ClickHouseConfig.fromEnv(),
                receiverConfig = ReceiverConfig.fromEnv(),
            )
        handle.set(receivers)

        // Keep main alive until the gRPC server terminates (driven by the shutdown hook on SIGTERM).
        receivers.grpc.awaitTermination()
    }
