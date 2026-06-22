package io.github.unityinflow.agenttracer.storage.migrations

import com.clickhouse.client.api.Client
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await

private val logger = KotlinLogging.logger {}

/**
 * Startup-applied, versioned, idempotent ClickHouse schema migration runner.
 *
 * ClickHouse has NO transactional DDL — there is no rollback if a multi-statement file
 * fails halfway. The runner is therefore *convergent* rather than atomic (RESEARCH
 * "Startup-Applied Versioned Migration Runner"):
 *  1. Create a `schema_migrations` tracking table (`IF NOT EXISTS`).
 *  2. List the ordered `migrations/00N_*.sql` classpath resources — lexical order = apply order.
 *  3. For each, `SELECT count() FROM schema_migrations WHERE Version = ?`; if `0`, split the
 *     file into statements (strip `--` line comments, THEN split on `;` — see
 *     [splitStatements]; client-v2 sends one statement per request), execute each statement
 *     (every statement is already `CREATE ... IF NOT EXISTS`), then record the applied
 *     (Version, Name) ONLY after every statement in the file succeeds.
 *
 * Because each statement is independently idempotent and the version is recorded only on full
 * success, a partial-failure re-run safely re-applies the whole file (already-created objects
 * become no-ops) and an already-applied version is skipped — re-running the runner is a no-op.
 *
 * Expected failures surface as [Result] (CLAUDE.md: `Result<T>` over exceptions); the runner
 * never throws for an already-applied version. All async uses coroutines (`.await()` over the
 * client-v2 `CompletableFuture`s) — no blocking `.get()`, no `Thread.sleep`.
 */
class MigrationRunner(
    private val client: Client,
    private val migrations: List<Migration> = DEFAULT_MIGRATIONS,
) {
    /**
     * A single migration: its monotonic [version], a human [name], and the classpath
     * [resourcePath] of the `.sql` file holding its (idempotent) DDL statements.
     */
    data class Migration(
        val version: Int,
        val name: String,
        val resourcePath: String,
    )

    /**
     * Applies every not-yet-recorded migration in order. Returns [Result.success] with the
     * count of migrations applied this run (`0` on a fully-converged schema), or
     * [Result.failure] if a DDL statement or tracking write fails.
     */
    suspend fun runMigrations(): Result<Int> =
        runCatching {
            ensureTrackingTable()
            val applied =
                migrations.count { migration ->
                    if (isApplied(migration.version)) {
                        logger.debug { "Migration ${migration.version} (${migration.name}) already applied — skipping" }
                        false
                    } else {
                        applyMigration(migration)
                        true
                    }
                }
            logger.info { "Migrations converged: $applied applied this run, ${migrations.size} total" }
            applied
        }

    private suspend fun ensureTrackingTable() {
        execute(
            """
            CREATE TABLE IF NOT EXISTS schema_migrations (
              Version UInt32,
              Name String,
              AppliedAt DateTime DEFAULT now()
            ) ENGINE = MergeTree
            ORDER BY Version
            """.trimIndent(),
        )
    }

    private suspend fun isApplied(version: Int): Boolean {
        val records =
            client
                .queryAll(
                    "SELECT count() AS c FROM schema_migrations WHERE Version = {v:UInt32}",
                    mapOf("v" to version),
                )
        val count = records.firstOrNull()?.getLong("c") ?: 0L
        return count > 0L
    }

    private suspend fun applyMigration(migration: Migration) {
        val sql =
            loadResource(migration.resourcePath)
                ?: error("Migration resource not found on classpath: ${migration.resourcePath}")
        val statements = splitStatements(sql)
        logger.info { "Applying migration ${migration.version} (${migration.name}): ${statements.size} statement(s)" }
        // Execute every statement; record the version only after ALL succeed (convergent).
        for (statement in statements) {
            execute(statement)
        }
        execute(
            "INSERT INTO schema_migrations (Version, Name) VALUES ({v:UInt32}, {n:String})",
            mapOf("v" to migration.version, "n" to migration.name),
        )
    }

    private suspend fun execute(
        sql: String,
        params: Map<String, Any> = emptyMap(),
    ) {
        // client-v2 execute(): non-blocking command for DDL/DML; bridged to a coroutine via await().
        if (params.isEmpty()) {
            client.execute(sql).await()
        } else {
            client.execute(sql, params).await()
        }
    }

    private fun loadResource(path: String): String? =
        javaClass.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }

    companion object {
        /**
         * Splits a multi-statement `.sql` file into individual statements (client-v2 sends one
         * statement per request).
         *
         * Comment-stripping happens BEFORE the `;` split: each line's `--` line comment is
         * removed first (keeping only the text before the first `--`), then the comment-free
         * text is split on `;`. This ordering is load-bearing — a `;` *inside* a `--` comment
         * (e.g. line 13 of `001_spans.sql`: `-- Pitfall 6: retention is TTL-only; never ...`)
         * must be gone before splitting, otherwise the comment's second half escapes the
         * per-line comment filter and leaks as garbage into the next statement (the Phase-2
         * UAT blocker: ClickHouse Code 62 SYNTAX_ERROR from unbalanced parentheses).
         *
         * Assumption: no `;` appears inside a SQL string literal in any migration (verified
         * true for all current `migrations/00N_*.sql` files). Handling `;` inside string
         * literals would require a fuller SQL tokenizer — deliberately not built for v0.0.1.
         */
        internal fun splitStatements(sql: String): List<String> =
            sql
                .lineSequence()
                .map { line -> line.substringBefore("--") }
                .joinToString("\n")
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        /** The ordered Phase-2 migration set; resource names sort lexically = apply order. */
        val DEFAULT_MIGRATIONS: List<Migration> =
            listOf(
                Migration(1, "spans", "migrations/001_spans.sql"),
                Migration(2, "payloads", "migrations/002_payloads.sql"),
                Migration(3, "trace_id_ts_mv", "migrations/003_trace_id_ts_mv.sql"),
                Migration(4, "agent_runs_mv", "migrations/004_agent_runs_mv.sql"),
            )
    }
}
