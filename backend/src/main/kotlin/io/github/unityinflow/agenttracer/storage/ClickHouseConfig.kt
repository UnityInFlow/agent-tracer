package io.github.unityinflow.agenttracer.storage

/**
 * Immutable ClickHouse connection configuration, sourced from the environment.
 *
 * Credentials NEVER live in source (CLAUDE.md hard rule, threat T-02-02): the [password]
 * has no baked-in default — an absent `CLICKHOUSE_PASSWORD` resolves to an empty string
 * (the ClickHouse default-user convention), never a literal secret. [url] and [database]
 * carry sane non-secret defaults so a local single-node ClickHouse needs no env wiring.
 *
 * Build instances with [fromEnv]; tests construct the data class directly with the
 * Testcontainers-generated endpoint/credentials.
 *
 * @property url ClickHouse HTTP endpoint, e.g. `http://localhost:8123/` (`CLICKHOUSE_URL`).
 * @property database target database (`CLICKHOUSE_DATABASE`, default `default`).
 * @property user ClickHouse user (`CLICKHOUSE_USER`, default `default`).
 * @property password ClickHouse password (`CLICKHOUSE_PASSWORD`); empty when unset — no literal default.
 */
data class ClickHouseConfig(
    val url: String,
    val database: String,
    val user: String,
    val password: String,
) {
    companion object {
        const val DEFAULT_URL = "http://localhost:8123/"
        const val DEFAULT_DATABASE = "default"
        const val DEFAULT_USER = "default"

        const val ENV_URL = "CLICKHOUSE_URL"
        const val ENV_DATABASE = "CLICKHOUSE_DATABASE"
        const val ENV_USER = "CLICKHOUSE_USER"
        const val ENV_PASSWORD = "CLICKHOUSE_PASSWORD"

        /**
         * Builds a [ClickHouseConfig] from environment variables. The password is read
         * verbatim from [ENV_PASSWORD] with an empty-string fallback — there is no
         * secret default in code (threat T-02-02).
         */
        fun fromEnv(env: (String) -> String? = System::getenv): ClickHouseConfig =
            ClickHouseConfig(
                url = env(ENV_URL) ?: DEFAULT_URL,
                database = env(ENV_DATABASE) ?: DEFAULT_DATABASE,
                user = env(ENV_USER) ?: DEFAULT_USER,
                // No literal password default — absent means empty (ClickHouse default user).
                password = env(ENV_PASSWORD) ?: "",
            )
    }
}
