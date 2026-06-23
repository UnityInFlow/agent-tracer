package io.github.unityinflow.agenttracer.ingest

/**
 * Immutable OTLP-receiver configuration, sourced from the environment.
 *
 * Supplies the two listening ports (:4318 HTTP, :4317 gRPC), the inbound size caps that
 * bound a single request on each transport (DoS guard, T-03-03), and the `Retry-After`
 * the HTTP path advertises under saturation (D-01). The 32 MiB defaults comfortably cover
 * SC2's ">4 MB" prompt-heavy spans (A4); none of these knobs is a secret.
 *
 * Build instances with [fromEnv]; tests construct the data class directly. The [init] block
 * fails fast on an invalid port or non-positive cap so misconfiguration surfaces at boot,
 * not at request time (T-03-03).
 *
 * @property httpPort OTLP/HTTP listen port (`AGENT_TRACER_HTTP_PORT`, default 4318).
 * @property grpcPort OTLP/gRPC listen port (`AGENT_TRACER_GRPC_PORT`, default 4317).
 * @property grpcMaxInboundBytes gRPC `maxInboundMessageSize` cap (`AGENT_TRACER_GRPC_MAX_INBOUND_BYTES`, default 32 MiB).
 * @property httpMaxBodyBytes HTTP request-body cap mirroring the gRPC bound (`AGENT_TRACER_HTTP_MAX_BODY_BYTES`, default 32 MiB).
 * @property retryAfterSeconds `Retry-After` advertised on a 429 under saturation (`AGENT_TRACER_RETRY_AFTER_SECONDS`, default 1).
 */
data class ReceiverConfig(
    val httpPort: Int,
    val grpcPort: Int,
    val grpcMaxInboundBytes: Int,
    val httpMaxBodyBytes: Int,
    val retryAfterSeconds: Int,
) {
    init {
        require(httpPort in 1..65535) { "httpPort must be in 1..65535, got $httpPort" }
        require(grpcPort in 1..65535) { "grpcPort must be in 1..65535, got $grpcPort" }
        require(httpPort != grpcPort) { "httpPort and grpcPort must differ, both were $httpPort" }
        require(grpcMaxInboundBytes > 0) { "grpcMaxInboundBytes must be > 0, got $grpcMaxInboundBytes" }
        require(httpMaxBodyBytes > 0) { "httpMaxBodyBytes must be > 0, got $httpMaxBodyBytes" }
        require(retryAfterSeconds >= 0) { "retryAfterSeconds must be >= 0, got $retryAfterSeconds" }
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 4318
        const val DEFAULT_GRPC_PORT = 4317

        /** 32 MiB — > SC2's ">4 MB" prompt-heavy gRPC case (A4); bounds a single request. */
        const val DEFAULT_GRPC_MAX_INBOUND_BYTES = 32 * 1024 * 1024

        /** 32 MiB — mirrors the gRPC cap so the HTTP path has the same DoS bound (symmetry). */
        const val DEFAULT_HTTP_MAX_BODY_BYTES = 32 * 1024 * 1024
        const val DEFAULT_RETRY_AFTER_SECONDS = 1

        const val ENV_HTTP_PORT = "AGENT_TRACER_HTTP_PORT"
        const val ENV_GRPC_PORT = "AGENT_TRACER_GRPC_PORT"
        const val ENV_GRPC_MAX_INBOUND_BYTES = "AGENT_TRACER_GRPC_MAX_INBOUND_BYTES"
        const val ENV_HTTP_MAX_BODY_BYTES = "AGENT_TRACER_HTTP_MAX_BODY_BYTES"
        const val ENV_RETRY_AFTER_SECONDS = "AGENT_TRACER_RETRY_AFTER_SECONDS"

        /**
         * Builds a [ReceiverConfig], overriding each knob from its `AGENT_TRACER_*` env var. An ABSENT
         * var uses the default; a var that is PRESENT-but-unparseable FAILS FAST with a clear message
         * naming the var (WR-04) — it is NOT silently swallowed into the default, since a port or
         * size-cap typo must surface at boot, not bind an unexpected value (the class KDoc's
         * "misconfiguration surfaces at boot" promise). The [init] block then validates the ranges.
         */
        fun fromEnv(env: (String) -> String? = System::getenv): ReceiverConfig =
            ReceiverConfig(
                httpPort = intEnv(env, ENV_HTTP_PORT, DEFAULT_HTTP_PORT),
                grpcPort = intEnv(env, ENV_GRPC_PORT, DEFAULT_GRPC_PORT),
                grpcMaxInboundBytes = intEnv(env, ENV_GRPC_MAX_INBOUND_BYTES, DEFAULT_GRPC_MAX_INBOUND_BYTES),
                httpMaxBodyBytes = intEnv(env, ENV_HTTP_MAX_BODY_BYTES, DEFAULT_HTTP_MAX_BODY_BYTES),
                retryAfterSeconds = intEnv(env, ENV_RETRY_AFTER_SECONDS, DEFAULT_RETRY_AFTER_SECONDS),
            )

        /**
         * Resolves one integer knob: absent → [default]; present-and-parseable → its value;
         * present-but-unparseable → fail fast with a message naming [key] (WR-04). Distinguishing
         * "unset" from "set but malformed" is the whole point — `toIntOrNull() ?: default` collapses
         * the two and hides a typo behind a silent default.
         */
        private fun intEnv(
            env: (String) -> String?,
            key: String,
            default: Int,
        ): Int {
            val raw = env(key) ?: return default
            return raw.toIntOrNull() ?: error("$key must be an integer, got '$raw'")
        }
    }
}
