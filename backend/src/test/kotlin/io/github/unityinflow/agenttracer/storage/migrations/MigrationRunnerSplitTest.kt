package io.github.unityinflow.agenttracer.storage.migrations

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Docker-free regression guard for [MigrationRunner.splitStatements].
 *
 * This is the test that would have caught the Phase-2 UAT blocker (ClickHouse Code: 62
 * SYNTAX_ERROR): a `;` inside a `--` line comment in `001_spans.sql` was leaking comment
 * text into the following `CREATE TABLE` statement because the old splitter split on `;`
 * BEFORE stripping comments. These assertions fail against the pre-fix splitter and pass
 * after comments are stripped first.
 *
 * Pure JVM — no Docker, no Testcontainers, no coroutines.
 */
class MigrationRunnerSplitTest {
    private fun split(sql: String): List<String> = MigrationRunner.splitStatements(sql)

    private fun String.balancedParens(): Boolean = count { it == '(' } == count { it == ')' }

    @Test
    fun semicolonInsideCommentNeverLeaksIntoStatement() {
        val sql =
            """
            -- retention is TTL-only; never ALTER.
            CREATE TABLE t (a Int32) ENGINE = Memory;
            """.trimIndent()

        val statements = split(sql)

        statements shouldHaveSize 1
        val stmt = statements.single()
        // No comment text leaked from the semicolon-bearing comment.
        stmt shouldNotContain "retention"
        stmt shouldNotContain "never ALTER"
        stmt shouldNotContain "--"
        // Balanced parentheses — the leaked fragment would unbalance them.
        stmt.balancedParens() shouldBe true
        stmt shouldContain "CREATE TABLE t (a Int32)"
    }

    @Test
    fun realSpansMigrationYieldsOneBalancedCreateTable() {
        val sql =
            javaClass.classLoader
                .getResourceAsStream("migrations/001_spans.sql")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("migrations/001_spans.sql not found on the test classpath")

        val statements = split(sql)

        statements shouldHaveSize 1
        val createTable = statements.single()
        createTable.trimStart().startsWith("CREATE TABLE") shouldBe true
        createTable.balancedParens() shouldBe true
        // The exact fragment the old splitter leaked from the line-13 comment.
        createTable shouldNotContain "never ALTER UPDATE/DELETE."
        createTable shouldNotContain "--"
        createTable shouldNotContain "Pitfall"
    }

    @Test
    fun multiStatementFileReturnsOneStatementPerRealSemicolon() {
        val sql =
            """
            -- a header comment; with a semicolon
            CREATE TABLE a (x Int32) ENGINE = Memory;
            CREATE TABLE b (y Int32) ENGINE = Memory;

            -- trailing comment
            """.trimIndent()

        val statements = split(sql)

        statements shouldHaveSize 2
        statements[0] shouldContain "CREATE TABLE a"
        statements[1] shouldContain "CREATE TABLE b"
        statements.forEach { it.balancedParens() shouldBe true }
    }

    @Test
    fun fullyCommentedFileReturnsEmptyList() {
        val sql =
            """
            -- only comments here
            -- another; with a semicolon
            -- and a final line
            """.trimIndent()

        split(sql) shouldHaveSize 0
    }
}
