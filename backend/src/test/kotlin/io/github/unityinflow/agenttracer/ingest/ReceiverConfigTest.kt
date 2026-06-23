package io.github.unityinflow.agenttracer.ingest

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [ReceiverConfig.fromEnv]'s env-resolution policy (WR-04): an ABSENT var uses the
 * default; a var that is PRESENT-but-unparseable FAILS FAST naming the var, rather than silently
 * collapsing into the default and binding an unexpected port / size cap. The `fromEnv` overload takes
 * a `(String) -> String?` lookup, so these tests inject a map-backed env with no real `System.getenv`.
 *
 * Every `@Test` uses a block body (not an expression-body `= matcher`) so none is silently skipped
 * (Pitfall 5).
 */
class ReceiverConfigTest {
    private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { key -> map[key] }
    }

    @Test
    fun absentVarsUseDefaults() {
        val config = ReceiverConfig.fromEnv(envOf())
        config.httpPort shouldBe ReceiverConfig.DEFAULT_HTTP_PORT
        config.grpcPort shouldBe ReceiverConfig.DEFAULT_GRPC_PORT
        config.grpcMaxInboundBytes shouldBe ReceiverConfig.DEFAULT_GRPC_MAX_INBOUND_BYTES
        config.httpMaxBodyBytes shouldBe ReceiverConfig.DEFAULT_HTTP_MAX_BODY_BYTES
        config.retryAfterSeconds shouldBe ReceiverConfig.DEFAULT_RETRY_AFTER_SECONDS
    }

    @Test
    fun presentParseableVarsOverrideDefaults() {
        val config =
            ReceiverConfig.fromEnv(
                envOf(
                    ReceiverConfig.ENV_HTTP_PORT to "9418",
                    ReceiverConfig.ENV_GRPC_PORT to "9417",
                    ReceiverConfig.ENV_RETRY_AFTER_SECONDS to "5",
                ),
            )
        config.httpPort shouldBe 9418
        config.grpcPort shouldBe 9417
        config.retryAfterSeconds shouldBe 5
    }

    @Test
    fun presentButMalformedPortFailsFastNamingTheVar() {
        val thrown =
            shouldThrow<IllegalStateException> {
                // A typo (leading 'l' instead of '1') must NOT silently fall back to the default port.
                ReceiverConfig.fromEnv(envOf(ReceiverConfig.ENV_HTTP_PORT to "l8080"))
            }
        thrown.message.shouldContain(ReceiverConfig.ENV_HTTP_PORT)
        thrown.message.shouldContain("l8080")
    }

    @Test
    fun presentButMalformedSizeCapFailsFastNamingTheVar() {
        val thrown =
            shouldThrow<IllegalStateException> {
                // "32MB" is not an Int — a security-relevant size cap must fail fast, not default.
                ReceiverConfig.fromEnv(envOf(ReceiverConfig.ENV_GRPC_MAX_INBOUND_BYTES to "32MB"))
            }
        thrown.message.shouldContain(ReceiverConfig.ENV_GRPC_MAX_INBOUND_BYTES)
    }
}
