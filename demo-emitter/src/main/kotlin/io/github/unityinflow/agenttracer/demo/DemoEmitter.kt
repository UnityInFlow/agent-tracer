package io.github.unityinflow.agenttracer.demo

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Immutable demo-emitter configuration, sourced from the environment, mirroring
 * the `ClickHouseConfig.fromEnv` discipline (named ENV constants, no scattered
 * raw `System.getenv`). All values are non-secret local config (threat T-04-07
 * accepted): a plaintext OTLP endpoint plus a seed and run count.
 *
 * @property endpoint the OTLP/HTTP traces endpoint (`OTLP_ENDPOINT`); defaults to
 *   the local receiver `http://localhost:4318/v1/traces` (Compose sets
 *   `http://tracer:4318/v1/traces`).
 * @property seed the [Random] seed (`DEMO_SEED`) — same seed reproduces the same
 *   span trees (SC4).
 * @property runs the number of runs to emit (`DEMO_RUNS`); operator-bounded
 *   (threat T-04-09 — caps the export burst alongside the bounded flush).
 */
data class DemoConfig(
    val endpoint: String,
    val seed: Long,
    val runs: Int,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces"
        const val DEFAULT_SEED = 42L
        const val DEFAULT_RUNS = 20

        const val ENV_ENDPOINT = "OTLP_ENDPOINT"
        const val ENV_SEED = "DEMO_SEED"
        const val ENV_RUNS = "DEMO_RUNS"

        /**
         * Builds a [DemoConfig] from environment variables. Unparseable
         * [ENV_SEED] / [ENV_RUNS] values fall back to their defaults (the emitter
         * is a dev tool — a typo should not crash the one-shot run).
         */
        fun fromEnv(env: (String) -> String? = System::getenv): DemoConfig =
            DemoConfig(
                endpoint = env(ENV_ENDPOINT) ?: DEFAULT_ENDPOINT,
                seed = env(ENV_SEED)?.toLongOrNull() ?: DEFAULT_SEED,
                runs = env(ENV_RUNS)?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_RUNS,
            )
    }
}

/** Bounded grace period for the final flush+exit (threat T-04-09). */
private const val FLUSH_TIMEOUT_SECONDS = 30L

/** OTel resource `service.name` for the synthetic demo runs. */
private const val SERVICE_NAME = "demo-agent"

/**
 * One-shot demo emitter (TOOL-01/SC4/D-06): exports [DemoConfig.runs] seeded,
 * full-convention agent runs over OTLP/HTTP to the receiver, then flushes and
 * exits. It is an OTLP *client* of the Phase-3 receiver and the sole producer of
 * context-saturation data.
 *
 * The drain is a bounded `shutdown().join(...)` (no blocking sleeps); the join
 * IS the one-shot flush barrier before the JVM exits.
 */
fun main() {
    val config = DemoConfig.fromEnv()
    println(
        "demo-emitter: endpoint=${config.endpoint} seed=${config.seed} runs=${config.runs}",
    )

    val exporter =
        OtlpHttpSpanExporter
            .builder()
            .setEndpoint(config.endpoint)
            .build()
    val tracerProvider =
        SdkTracerProvider
            .builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .setResource(
                Resource.create(
                    Attributes.of(AttributeKey.stringKey("service.name"), SERVICE_NAME),
                ),
            ).build()

    try {
        val tracer = tracerProvider.get("demo-emitter")
        val factory = SeededRunFactory(Random(config.seed))
        repeat(config.runs) { factory.generateRun(tracer) }
    } finally {
        // Bounded one-shot drain: flush the BatchSpanProcessor then close, capped
        // at FLUSH_TIMEOUT_SECONDS so a hung receiver cannot block forever.
        val flushed =
            tracerProvider
                .shutdown()
                .join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .isSuccess
        exporter.close()
        println("demo-emitter: emitted ${config.runs} runs, flush success=$flushed")
    }
}
