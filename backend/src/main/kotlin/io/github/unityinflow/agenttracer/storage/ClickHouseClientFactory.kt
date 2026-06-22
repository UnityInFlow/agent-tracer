package io.github.unityinflow.agenttracer.storage

import com.clickhouse.client.api.Client

/**
 * Builds client-v2 [Client] instances from a [ClickHouseConfig].
 *
 * Centralises the `Client.Builder` wiring (endpoint, credentials, request compression,
 * default database) so the connection shape lives in one place. The returned [Client] is
 * `AutoCloseable`; callers own its lifecycle and must [Client.close] it on shutdown.
 *
 * `compressClientRequest(true)` gzips request bodies — worthwhile for the fat payload/batch
 * inserts the storage layer streams (Decision 4).
 */
object ClickHouseClientFactory {
    /**
     * Builds a fresh [Client] bound to [config]. Credentials come from [config] (which sources
     * them from the environment via [ClickHouseConfig.fromEnv]) — never from literals.
     */
    fun create(config: ClickHouseConfig): Client =
        Client
            .Builder()
            .addEndpoint(config.url)
            .setUsername(config.user)
            .setPassword(config.password)
            .setDefaultDatabase(config.database)
            .compressClientRequest(true)
            .build()
}
