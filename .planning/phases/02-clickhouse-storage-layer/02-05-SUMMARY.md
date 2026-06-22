---
phase: 02-clickhouse-storage-layer
plan: 05
subsystem: storage
tags: [clickhouse, migrations, testcontainers, gap-closure, uat-blocker]
requires:
  - 02-02-PLAN.md (DDL migrations + MigrationRunner)
  - 02-03-PLAN.md (SpanRepository + storage ITs)
  - 02-04-PLAN.md (batching pipeline + PartCountIT)
provides:
  - Comment-safe SQL statement splitting (Code 62 SYNTAX_ERROR closed)
  - SimpleAggregateFunction-free key/TTL columns in both MVs (Code 549 closed)
  - Observed-green storage ITs against real ClickHouse on Docker Desktop
affects:
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/migrations/MigrationRunner.kt
  - backend/src/main/resources/migrations/003_trace_id_ts_mv.sql
  - backend/src/main/resources/migrations/004_agent_runs_mv.sql
tech-stack:
  added: []
  patterns:
    - "Strip -- line comments before splitting on ; (no SQL tokenizer for v0.0.1)"
    - "AggregatingMergeTree key/PARTITION/TTL keys off plain DateTime columns, never SimpleAggregateFunction"
    - "Expression-body @Test methods MUST declare ': Unit' or JUnit silently skips them"
key-files:
  created: []
  modified:
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/migrations/MigrationRunner.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/migrations/MigrationRunnerSplitTest.kt
    - backend/src/main/resources/migrations/003_trace_id_ts_mv.sql
    - backend/src/main/resources/migrations/004_agent_runs_mv.sql
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSchemaIT.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/PartCountIT.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepositoryIT.kt
decisions:
  - "D-07 (Option A): duplicate/late/out-of-order span dedup deferred to Phase 4; Phase-2 agent_runs rollup asserts honest 3-distinct-span math (no dedup test fiction)"
  - "Schema key/TTL columns must be plain DateTime, never SimpleAggregateFunction (ClickHouse Code 549)"
metrics:
  duration: ~25 min
  completed: 2026-06-22
---

# Phase 2 Plan 05: Gap Closure — Comment-Safe SQL Splitting + Observed-Green Storage ITs Summary

Closed the 02-UAT.md blocker (ClickHouse schema never applied, Code 62 SYNTAX_ERROR) by fixing the migration statement splitter to strip `--` comments before splitting on `;`, then resolved a chain of secondary defects surfaced once the schema finally executed against real ClickHouse — landing all 65 backend tests green on Docker Desktop with no Code 62 and no Code 549.

## What Was Built

This is a gap-closure plan executed across two committed sessions:

### Task 1 — Comment-safe SQL splitting (committed earlier, `e6817f4`)
`MigrationRunner.splitStatements()` previously split each `.sql` file on `;` BEFORE stripping `--` line comments. The header comment on line 13 of `001_spans.sql` — `-- Pitfall 6: retention is TTL-only; never ALTER UPDATE/DELETE.` — contains a `;`. The naive split cut that comment in two; its tail (`never ALTER UPDATE/DELETE.`) no longer began with `--`, escaped the per-line comment filter, and was prepended as garbage to `CREATE TABLE spans (...)`, producing unmatched parentheses → ClickHouse Code 62 SYNTAX_ERROR → schema never applied → all three storage ITs failed at setup. The fix strips comments first, then splits; a Docker-free `MigrationRunnerSplitTest` regression-guards it (asserts no comment text leaks and parentheses stay balanced, including against the real `001_spans.sql` resource).

### Task 2 — Schema key/TTL fixes + Phase-2 rollup semantics + observed-green run (this session, `eaa045e`)
With the splitter fixed, the DDL finally reached ClickHouse and exposed defects the broken Colima/QEMU context had masked all phase long. Each was fixed minimally without weakening any assertion.

## Deviations from Plan

The plan's stated Task 2 was "run the three ITs — the splitter fix is the ONLY change needed." In practice, executing the DDL against real ClickHouse for the first time surfaced four genuine defects that the prior grep-substituted verification could never have caught. All were fixed under deviation Rule 1 (bugs) / Rule 3 (blocking issues), bundled into the single green Task-2 commit so no failing test was ever committed.

### Auto-fixed Issues

**1. [Rule 1 - Bug] ClickHouse Code 549: SimpleAggregateFunction in key/TTL/PARTITION expressions**
- **Found during:** Task 2 (first real schema apply)
- **Issue:** Both MVs keyed PARTITION/ORDER BY/TTL off `SimpleAggregateFunction` columns (`MaxTime` in `trace_id_ts`, `StartTime`/`EndTime` in `agent_runs`). ClickHouse forbids this — Code 549 `DATA_TYPE_CANNOT_BE_USED_IN_KEY`.
- **Fix:** Added dedicated plain `DateTime`/`DateTime64(9)` key columns (`TtlTime` on 003; `StartTs`/`EndTs` on 004), fed by the MV from the same `min/max(Timestamp)`. For a per-trace bounded set the last-merged plain value equals the trace's min/max timestamp, so partitioning, time-ordering and 14-day retention are unchanged; the aggregate `Min/Max/Start/End` columns are retained for reads. No assertion relaxed.
- **Files modified:** `003_trace_id_ts_mv.sql`, `004_agent_runs_mv.sql`
- **Commit:** `eaa045e`

**2. [Rule 1 - Bug] JUnit silently skipped expression-body `@Test` methods (verification-masking)**
- **Found during:** Task 2 — the most important finding. Kotlin expression-body `@Test` functions (`fun x() = runBlocking { ... }`) infer a return type other than `Unit` (here the lambda's value), and JUnit 5 silently does NOT execute `@Test` methods whose return type is not `void`/`Unit`. Several storage IT methods were therefore being skipped with zero signal — the suite reported "passing" while genuinely never asserting anything. This is what let the schema bugs hide.
- **Fix:** Added explicit `: Unit` return type to the affected `@Test` methods in `ClickHouseSchemaIT` and `SpanRepositoryIT`, forcing JUnit to run them. The final report shows 0 skipped across all 65 tests.
- **Files modified:** `ClickHouseSchemaIT.kt`, `SpanRepositoryIT.kt`
- **Commit:** `eaa045e`

**3. [Rule 1 - Bug] `getString(0)` zero-based vs ClickHouse 1-based column access**
- **Found during:** Task 2 (once `traceByIdPrunesGranules` actually ran)
- **Issue:** EXPLAIN-output read via `getString(0)` — the client's positional accessor is 1-based / the index was wrong for the `explain` column.
- **Fix:** Switched to `getString("explain")` (name-based), which is unambiguous.
- **Files modified:** `ClickHouseSchemaIT.kt`
- **Commit:** `eaa045e`

**4. [Rule 1 - Bug] PartCountIT fixtures fell outside the 14-day TTL window**
- **Found during:** Task 2 (once PartCountIT actually instantiated against real ClickHouse)
- **Issue:** Fixture timestamps were old enough that the new TTL (now keyed off plain `DateTime`) expired/partitioned them unexpectedly, perturbing the bounded-parts assertion.
- **Fix:** Re-anchored fixture timestamps into the active 14-day TTL window. The SC4 part-count-bounded assertion is unchanged.
- **Files modified:** `PartCountIT.kt`
- **Commit:** `eaa045e`

### Human Decision (D-07 / Option A)

`SpanRepositoryIT.agentRunsRollupIsCorrectWithDedup` inserted a 3-span trace, re-sent one span (duplicate redelivery), and asserted the rollup deduped to `spanCount=3`. Against real ClickHouse the `agent_runs` AggregatingMergeTree sums per-batch and returns 4 — correct Phase-2 behavior, because documented decision **D-07 defers duplicate/late/out-of-order span handling to Phase 4**. The original test encoded dedup behavior that does not (and per D-07 should not yet) exist.

The user chose **Option A** via the orchestrator: assert genuine Phase-2 rollup math rather than implement dedup.
- Removed the duplicate-span re-insert line; the test now exercises a real 3-distinct-span trace.
- `spanCount shouldBe 3L` retained — now the honest count of the 3 distinct spans.
- All other rollup assertions (summed tokens 3600/1050, summed cost 0.1275, max saturation 62.5) intact.
- Renamed `agentRunsRollupIsCorrectWithDedup` → `agentRunsRollupIsCorrect`, with an inline comment and class-level KDoc entry referencing D-07 (dedup deferred to Phase 4).
- Rejected Option B (implement span dedup now / change spans/agent_runs engine choice) — explicitly NOT done.

## Verification Evidence

Ran the full backend suite against a REAL ClickHouse on the machine's healthy Docker Desktop context (`docker context show` → `desktop-linux`), not the broken Colima/QEMU context that masked these defects all phase:

```
DOCKER_HOST=unix:///Users/jirihermann/.docker/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :backend:test
→ BUILD SUCCESSFUL in 20s
```

Exact JUnit XML totals: **65 tests, 0 failures, 0 errors, 0 skipped.**

Storage ITs (real ClickHouse, image `clickhouse/clickhouse-server:26.3.x`):

| IT class | tests | failures | errors | skipped |
|----------|-------|----------|--------|---------|
| ClickHouseSchemaIT | 4 | 0 | 0 | 0 |
| SpanRepositoryIT | 6 | 0 | 0 | 0 |
| PartCountIT | 1 | 0 | 0 | 0 |
| MigrationRunnerSplitTest (Docker-free) | 4 | 0 | 0 | 0 |

The schema applied cleanly with **no Code 62 SYNTAX_ERROR and no Code 549 DATA_TYPE_CANNOT_BE_USED_IN_KEY**. SC1 (typed-column round-trip), SC2 (EXPLAIN granule pruning), SC3 (payload round-trip incl. truncation), and SC4 (bounded part count < 300 over sustained load) are now backed by observed-green integration evidence — not grep-only structural claims.

## Success Criteria

- [x] Diagnosed blocker (Code 62) closed — schema applies cleanly against real ClickHouse.
- [x] Secondary Code 549 schema-key defect closed (plain DateTime key columns).
- [x] JUnit silently-skipped-expression-body finding closed (`: Unit`) — the masking root cause.
- [x] Comment-safe splitting regression-guarded Docker-free (MigrationRunnerSplitTest).
- [x] D-07 Option A applied — Phase-2 rollup test asserts honest 3-span math; dedup deferred to Phase 4.
- [x] 65/65 tests green on Docker Desktop, 0 skipped.
- [x] Kotlin conventions honored (no `var`, no new `!!`, coroutines unchanged).

## Notes on ktlint

The ecosystem CLAUDE.md mandates `ktlint` before commit, but no ktlint/spotless/detekt Gradle plugin is currently wired into this project's build (pre-existing state, out of scope for this gap-closure). Edits were kept style-clean by hand: functional splitter, no `var`, no new `!!`. Wiring the ktlint plugin is a separate tooling task.

## Self-Check: PASSED

- SUMMARY + both migration files present on disk.
- Commits `e6817f4` (Task 1) and `eaa045e` (Task 2) present in git history.
