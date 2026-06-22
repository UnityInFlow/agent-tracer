package io.github.unityinflow.agenttracer.storage

import com.clickhouse.client.api.Client
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared, JVM-wide ClickHouse fixture for every storage integration test (`*IT`).
 *
 * A single [ClickHouseContainer] pinned to `clickhouse/clickhouse-server:26.3` is started
 * lazily on first access and reused across all IT classes, so the ~90s spin-up is paid once
 * per test run (VALIDATION estimate) rather than per class. Testcontainers' Ryuk reaper tears
 * the container down at JVM exit — no manual stop and no `Thread.sleep` readiness loop; the
 * container's built-in wait strategy gates [container] on a ready server.
 *
 * [newClient] builds a fresh client-v2 [Client] against the running container, reading the
 * container-generated host / mapped port / username / password from the Testcontainers API —
 * never hardcoded credential literals (threat T-02-01).
 */
object ClickHouseTestSupport {
    /** Pinned LTS image — never `:latest` (non-reproducible). */
    private const val IMAGE = "clickhouse/clickhouse-server:26.3"

    /** ClickHouse native HTTP protocol port; Testcontainers maps it to a random host port. */
    private const val HTTP_PORT = 8123

    /**
     * The single shared container. `by lazy` guarantees one instance is started exactly once
     * for the whole JVM and reused by every IT that touches storage.
     */
    val container: ClickHouseContainer by lazy {
        ClickHouseContainer(DockerImageName.parse(IMAGE))
            .also { it.start() }
    }

    /**
     * Builds a new client-v2 [Client] bound to the shared container. Credentials and the mapped
     * port are read from the container API — no literals (threat T-02-01).
     */
    fun newClient(): Client =
        Client
            .Builder()
            .addEndpoint("http://${container.host}:${container.getMappedPort(HTTP_PORT)}/")
            .setUsername(container.username)
            .setPassword(container.password)
            .compressClientRequest(true)
            .build()
}
