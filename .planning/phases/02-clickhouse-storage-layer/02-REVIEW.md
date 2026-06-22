---
phase: 02-clickhouse-storage-layer
reviewed: 2026-06-22T00:00:00Z
depth: standard
files_reviewed: 7
files_reviewed_list:
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/migrations/MigrationRunner.kt
  - backend/src/main/resources/migrations/003_trace_id_ts_mv.sql
  - backend/src/main/resources/migrations/004_agent_runs_mv.sql
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSchemaIT.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/PartCountIT.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepositoryIT.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/migrations/MigrationRunnerSplitTest.kt
findings:
  critical: 0
  warning: 3
  info: 4
  total: 7
status: issues
---

# Phase 02: Code Review Report

**Reviewed:** 2026-06-22
**Depth:** standard
**Files Reviewed:** 7
**Status:** issues_found

## Summary

Reviewed the seven source files changed by the Phase-02 gap-closure run (plan 02-05, commits `e6817f4..8b10fa0`). The run closes a real UAT blocker (ClickHouse Code 62 / Code 549) with the right shape: comment-stripping-before-split, plain DateTime key columns for the MV target tables, and explicit `: Unit` return types to un-skip silently-dropped expression-body tests.

The targeted concern about silently-skipped `@Test` methods **checks out clean**: all expression-body tests in `ClickHouseSchemaIT` and `SpanRepositoryIT` now carry `(): Unit =`, and every test in `PartCountIT` and `MigrationRunnerSplitTest` is block-bodied (naturally Unit-returning), so none remain at risk.

No Critical findings. Three Warnings concern the durability of the new MV schema fixes under the multi-batch / out-of-order conditions the migrations themselves explicitly acknowledge but do not test, plus a real correctness gap in `splitStatements` beyond its own documented assumption. Info items cover convention drift and test fragility.

## Warnings

### WR-01: `agent_runs` PARTITION BY a plain per-batch column splits multi-batch traces — `FINAL` cannot reconcile them

**File:** `backend/src/main/resources/migrations/004_agent_runs_mv.sql:39-41` (and read path `ClickHouseSpanRepository.listRuns` → `agent_runs FINAL`)
**Issue:** `StartTs`/`EndTs` are plain (non-`SimpleAggregateFunction`) `DateTime64(9)` columns fed per insert block by the MV's `min(Timestamp)`/`max(Timestamp)`. `AggregatingMergeTree` only merge-combines the aggregate-function columns; a plain column keeps an *unspecified* row's value on merge. The table is `PARTITION BY toDate(StartTs)` and read via `FINAL`, but **`FINAL` only collapses parts within a single partition**. The MV's `GROUP BY TraceId` groups only within one insert block, so each write batch emits a partial-aggregate row whose `StartTs` is that batch's local `min(Timestamp)`. A trace whose spans (a) cross a calendar-day boundary or (b) arrive in more than one write batch can therefore land partial rows in two different `toDate(StartTs)` partitions that `FINAL` will never merge — yielding duplicate/split run rows for one trace, with each row carrying only a partial sum/max. The schema comment's claim ("the last-merged plain value equals the trace's min/max timestamp") is incorrect about ClickHouse merge semantics and silent about the cross-partition case. `agentRunsRollupIsCorrect` writes a single batch in a single partition, so it never exercises this.
**Fix:** Either partition the rollup on a deterministic, batch-independent key (e.g. derive the day from the trace itself, or `PARTITION BY tuple()` as `trace_id_ts` does), or make `StartTs`/`EndTs` `SimpleAggregateFunction(min/max, DateTime64(9))` plain-typed via a materialized expression so the partition key is stable across batches. At minimum, add an IT that writes a single trace in two batches (and one spanning a day boundary) and asserts `listRuns` returns exactly one fully-aggregated row.

### WR-02: `TtlTime`/`StartTs`/`EndTs` rely on a non-deterministic plain-column merge value, not `max()` — retention can expire early/late under out-of-order spans

**File:** `backend/src/main/resources/migrations/003_trace_id_ts_mv.sql:25,29` and `backend/src/main/resources/migrations/004_agent_runs_mv.sql:31-32,41`
**Issue:** The TTL keys off plain `TtlTime` (003) / `EndTs` (004), justified by "in an AggregatingMergeTree a non-aggregate column takes the last-merged value, which for a per-trace bounded set equals the trace's max timestamp." That equivalence does not hold: for a non-aggregate column AggregatingMergeTree keeps the value of an arbitrary row chosen during merge, **not** the max. For complete/in-order single-batch traces it happens to coincide, but the migrations' own comments say late/out-of-order/multi-batch spans are a real (Phase-4-deferred) scenario. Under those, a later merge can pick an earlier `TtlTime`, expiring the lookup/rollup row before the spans it indexes (orphaning the opposite of what the comment promises), or a later one, retaining it past the spans. The 14-day window makes the day-to-day blast radius small, but the correctness justification in the comment is wrong and the behaviour is non-deterministic.
**Fix:** Make the TTL key columns aggregate-driven instead of plain: store the retention anchor as `SimpleAggregateFunction(max, DateTime)` and TTL off `toDateTime(<aggregate>)`, or compute the partition/TTL key from an aggregate at read/merge time. If the plain-column approach is kept deliberately for v0.0.1, correct the comment to state the value is non-deterministic-on-merge and add a regression IT inserting out-of-order spans across two batches that asserts the row is not expired prematurely.

### WR-03: `splitStatements` truncates on `--` (and ignores `/* */`) inside any non-comment context — silently corrupts a future migration

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/migrations/MigrationRunner.kt:142-149`
**Issue:** The splitter does `line.substringBefore("--")`. Its docstring only disclaims `;` inside string literals, but the same logic mis-handles two further cases it does not mention: (1) a `--` appearing inside a string literal or identifier (e.g. `DEFAULT '--'`, a comment marker inside a `Comment '...'` clause, or a CODEC/regex argument) is treated as a comment and the rest of the line is silently dropped, producing a truncated statement that may still parse — corrupting DDL without an error; (2) block comments `/* ... */` are not stripped at all, so a `;` inside one would leak exactly like the original UAT bug. No current `00N_*.sql` triggers either case, so this is latent, but the splitter is the load-bearing fix for a Code-62 incident and the next migration author has no guardrail. Comment-stripping at the raw-text level (no literal/escape awareness) is inherently fragile for the role it now plays.
**Fix:** Document the *full* set of assumptions (no `--` inside literals/identifiers; no `/* */` block comments) directly above `splitStatements`, and add an `internal` validation/regression test asserting balanced parentheses and quote counts on every real migration file (extend `realSpansMigrationYieldsOneBalancedCreateTable` to iterate `DEFAULT_MIGRATIONS`). Longer term, replace the line-based stripper with a minimal state-machine tokenizer that respects single-quote string literals and `/* */` blocks.

## Info

### IN-01: `!!` without a safety comment violates CLAUDE.md (pre-existing, in a file this run edited)

**File:** `backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepositoryIT.kt:71-72`
**Issue:** `got.costUsd!!` and `got.contextSaturationPct!!` use the non-null assertion operator. CLAUDE.md (both ecosystem and tool-level) forbids `!!` without a comment explaining why it is safe; the nearby comment explains *what* is asserted, not *why* the value is non-null. These lines are pre-existing (not in the 02-05 diff), but the file was modified by this run. Note also the ecosystem CLAUDE.md scopes the `!!` ban as a production rule; flagging here as Info for convention consistency, not as a blocker.
**Fix:** Prefer `got.costUsd.shouldNotBeNull() shouldBeExactly 0.0425` (Kotest already imported) so the assertion fails with a clear message instead of an NPE, or add a one-line `// non-null: full() fixture always sets costUsd` comment.

### IN-02: `splitStatements` is documented as relying on a specific comment in `001_spans.sql` by line number

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/migrations/MigrationRunner.kt:135` and `.../MigrationRunnerSplitTest.kt:61`
**Issue:** The docstring and a test comment reference "line 13 of `001_spans.sql`" / "the line-13 comment." Line-number references rot the moment that file is edited (it is currently 58 lines and likely to grow). The behavioural assertions (`shouldNotContain "never ALTER UPDATE/DELETE."`) are robust; the line-number prose is not.
**Fix:** Drop the "line 13" references and describe the case by content ("the `retention is TTL-only; never ALTER` comment containing a semicolon").

### IN-03: `getOrThrow()` discards the `Result` failure cause framing the migration runner deliberately returns

**File:** `backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSchemaIT.kt:61`, `PartCountIT.kt:43`, `SpanRepositoryIT.kt:48`
**Issue:** The runner intentionally surfaces expected failures as `Result<Int>` (CLAUDE.md: `Result<T>` over exceptions). Tests immediately `getOrThrow()`. That is acceptable in a test (fail-fast is fine), but it means a `@BeforeAll` migration failure throws inside `runBlocking` and aborts the whole class with the raw cause rather than an assertion — slightly noisier diagnostics. Minor; no action required beyond awareness.
**Fix:** Optional — wrap with `.onFailure { ... }` or a Kotest `shouldNotThrowAny` for a clearer failure surface; not necessary for correctness.

### IN-04: `PartCountIT` TTL-window anchoring is time-dependent and can flake near midnight UTC

**File:** `backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/PartCountIT.kt:78,83-84` and `PARTITION_DAYS=12`
**Issue:** `anchorDay = Instant.now().minusSeconds(86_400)` then cycles `dayOffset` over `PARTITION_DAYS=12`, so the oldest partition is ~13 days before now. The `spans` TTL is 14 days. The margin is ~1 day; a slow CI run that straddles a UTC day boundary, or any future bump to `PARTITION_DAYS`/test latency, can push the oldest partition past the 14-day TTL and drop rows, making the `count() >= expected` assertion flake. The re-anchoring fix is correct in intent but leaves a thin safety margin.
**Fix:** Widen the margin (anchor at `now()` rather than `now() - 1d`, or assert `PARTITION_DAYS + 2 < 14` as an invariant), or seed against a frozen clock so the test is deterministic regardless of wall-clock time.

---

_Reviewed: 2026-06-22_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
