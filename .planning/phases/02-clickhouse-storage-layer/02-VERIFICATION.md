---
phase: 02-clickhouse-storage-layer
verified: 2026-06-22T16:50:00Z
status: passed
score: 4/4 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 4/4 (structural/unit only — Testcontainers ITs unobserved under Colima/QEMU)
  gaps_closed:
    - "Schema applies cleanly against real ClickHouse (Code 62 SYNTAX_ERROR closed by comment-safe SQL splitter)"
    - "Code 549 DATA_TYPE_CANNOT_BE_USED_IN_KEY closed (plain DateTime key/TTL columns)"
    - "JUnit silently-skipped expression-body @Test masking closed (explicit : Unit) — the root cause that fooled earlier verification"
    - "SC1/SC2/SC3/SC4 now backed by OBSERVED-GREEN Testcontainers integration evidence (verifier re-ran the suite independently with --rerun-tasks: 65/0/0)"
  gaps_remaining: []
  regressions: []
deferred:
  - truth: "agent_runs rollup reconciles a single trace whose spans cross a calendar-day boundary or arrive across multiple write batches into exactly one fully-aggregated run row (and TTL expiry tracks the trace's true max timestamp deterministically)"
    addressed_in: "Phase 4"
    evidence: "Decision D-07 (DISCUSSION-LOG line 47 + ROADMAP line 74): late/out-of-order/multi-batch span reconciliation deferred to Phase 4; AggregatingMergeTree+SimpleAggregateFunction engine deliberately chosen to keep that fix reachable WITHOUT a table rewrite. REVIEW WR-01/WR-02 flag this exact non-determinism. No Phase-2 success criterion (SC1-SC4) depends on multi-batch agent_runs reconciliation."
---

# Phase 02: ClickHouse Storage Layer Verification Report

**Phase Goal:** Lay the ClickHouse storage layer — schema with typed agent columns, materialized views (trace_id_ts for granule-pruned trace-by-id, agent_runs rollup), payload storage with truncation metadata, and the batching write pipeline (bounded coroutine Channel, count/time flush, bounded-backoff retry, backpressure). Every "fix later = table rewrite" schema decision is pinned here.
**Verified:** 2026-06-22
**Status:** passed
**Re-verification:** Yes — after UAT-blocker gap closure (plan 02-05). Previous status was `human_needed` (ITs unobserved); this run independently OBSERVED them green.

## Re-Verification Summary

The prior `02-VERIFICATION.md` was `human_needed` because the three storage ITs could not start a container under the local Colima/QEMU Docker host — so SC1/SC2/SC3 and the SC4 bounded-parts assertion were only structurally/unit-verified. The UAT (02-UAT.md) then ran the suite against healthy Docker Desktop and surfaced a genuine, masked blocker: the schema never applied (Code 62 SYNTAX_ERROR), and several IT `@Test` methods were being silently skipped by JUnit. Gap-closure plan 02-05 fixed both root causes and a chain of secondary defects.

**This verifier independently re-ran the full backend suite against real ClickHouse** (not trusting the SUMMARY):

```
DOCKER_HOST=unix:///Users/jirihermann/.docker/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./gradlew :backend:test --rerun-tasks
→ BUILD SUCCESSFUL in 28s
```

Parsed JUnit XML totals (all 13 classes): **tests=65 skipped=0 failures=0 errors=0.** Storage ITs ran and passed: `ClickHouseSchemaIT`=4, `SpanRepositoryIT`=6, `PartCountIT`=1, all with `skipped=0` — proving the `: Unit` fix genuinely un-skipped the previously-masked tests. `--rerun-tasks` forced fresh execution (no cache). Docker context confirmed `desktop-linux`; ClickHouse image pinned `clickhouse/clickhouse-server:26.3`.

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Spans land in ClickHouse with typed first-class columns for agent fields (tokens, cost, saturation, skill, model) directly SQL-queryable — not in a generic attribute map | ✓ VERIFIED | `001_spans.sql` promotes InputTokens/OutputTokens/CostUsd/ContextSaturationPct/SkillName/Model/AgentName/etc. as typed columns (Nullable metrics held OUT of ORDER BY per D-01; LowCardinality dims per D-02; Maps hold only leftovers per Pitfall 3). **`SpanRepositoryIT.typedAgentColumnsRoundTrip` OBSERVED PASS** — asserts each typed column round-trips AND that agent dims are NOT smuggled into `spanAttributes`. |
| 2 | Trace lookup by trace_id served via the MV path, verified to avoid a full table scan | ✓ VERIFIED | `003_trace_id_ts_mv.sql` AggregatingMergeTree + MV; `ClickHouseSpanRepository.getTraceById` does the two-step lookup (range from `trace_id_ts FINAL`, then bounded `spans` scan, no FINAL on hot path). **`ClickHouseSchemaIT.traceByIdPrunesGranules` OBSERVED PASS** — seeds 12 background partitions, runs `EXPLAIN indexes=1`, asserts `selected < total` granules (genuine pruning). `traceIdTsReturnsCorrectRange` confirms (Min,Max). |
| 3 | A span's input/output payloads can be stored and retrieved individually, sufficient for replay + span detail panel | ✓ VERIFIED | `002_payloads.sql` separate table keyed (TraceId,SpanId); `getPayload` parameterized point lookup → `PayloadRow?` (null when absent). **`SpanRepositoryIT.payloadsRoundTripIncludingTruncation` + `getPayloadForAbsentSpanReturnsNull` OBSERVED PASS** — verbatim body round-trip, cap'd-body + truncation metadata round-trip, absent→null. |
| 4 | Writes flow through a batching pipeline (flush by count/time); sustained load keeps part counts bounded, proven against real ClickHouse via Testcontainers | ✓ VERIFIED | `SpanWriter` bounded `Channel(capacity)`, single drain coroutine, flush at batchSize OR flushInterval via `select{}/onTimeout`, retry-then-retain (no drop) + StateFlow backpressure, `drainAndClose` tail flush. `SpanWriterTest` (5) OBSERVED PASS (flush-by-count/time, retry-no-drop, suspend-on-full, drain). **`PartCountIT.sustainedBatchedLoadKeepsActivePartCountBounded` OBSERVED PASS** — 30k spans across 30 traces / 12 partitions through the real SpanWriter→repo path, asserts all rows visible AND `system.parts` active < 300 (SC4 against real ClickHouse). |

**Score:** 4/4 truths verified with observed-green integration evidence. No structural/grep substitution remains.

### Deferred Items

| # | Item | Addressed In | Evidence |
|---|------|-------------|----------|
| 1 | Deterministic multi-batch / cross-day-boundary `agent_runs` reconciliation + deterministic TTL anchor (REVIEW WR-01/WR-02) | Phase 4 | D-07 explicitly defers late/out-of-order/multi-batch span handling to Phase 4; engine choice keeps the fix table-rewrite-free. No SC1-SC4 depends on it. |

### Required Artifacts

| Artifact | Expected | Status | Details |
| --- | --- | --- | --- |
| `migrations/001_spans.sql` | Wide MergeTree, typed agent cols, Nullable metrics out of ORDER BY, bloom filter, 14d TTL | ✓ VERIFIED | TraceId last in ORDER BY; no Nullable in key; bloom_filter; Delta+ZSTD; TTL 14 DAY |
| `migrations/002_payloads.sql` | Separate (TraceId,SpanId) table, truncation cols, same TTL | ✓ VERIFIED | Present; round-trip proven by IT |
| `migrations/003_trace_id_ts_mv.sql` | trace_id_ts target + MV (STOR-02); plain DateTime TTL key | ✓ VERIFIED | `TtlTime DateTime` plain key (Code 549 fix); MV TO trace_id_ts |
| `migrations/004_agent_runs_mv.sql` | agent_runs AggregatingMergeTree rollup + MV; plain DateTime key cols | ✓ VERIFIED | `StartTs`/`EndTs DateTime64(9)` plain key/PARTITION/TTL (Code 549 fix); aggregates retained for reads |
| `MigrationRunner.kt` | Convergent idempotent runner; comment-safe statement splitter | ✓ VERIFIED | `splitStatements` strips `--` via `substringBefore("--")` BEFORE `split(';')` (Code 62 fix); version recorded only after all statements succeed |
| `ClickHouseSpanRepository.kt` | Parameterized impl, two-step MV lookup, point getPayload, filtered listRuns | ✓ VERIFIED | All reads `{name:Type}` bound; RowBinary POJO inserts; Result<T> everywhere |
| `SpanWriter.kt` / `BackpressureSignal.kt` | Bounded Channel batcher + StateFlow signal | ✓ VERIFIED | Bounded Channel, no-drop, drainAndClose; proven by SpanWriterTest + PartCountIT |
| `AgentSpanRowMapper.kt` | (AgentSpan,SpanContext)→rows, 256KB byte cap + truncation | ✓ VERIFIED | AgentSpanRowMapperTest (7) PASS |
| `MigrationRunnerSplitTest.kt` | Docker-free regression guard for the splitter | ✓ VERIFIED | 4 tests PASS: semicolon-in-comment never leaks, real 001_spans.sql yields one balanced CREATE TABLE, multi-statement count, fully-commented→empty |

### Key Link Verification

| From | To | Via | Status | Details |
| --- | --- | --- | --- | --- |
| MigrationRunner.splitStatements | 001_spans.sql line-13 comment | comment-strip-before-split | ✓ WIRED | `substringBefore("--")` then `split(';')` — Code 62 closed, regression-guarded |
| SpanWriter | repo.writeBatch | flush → writeBatch | ✓ WIRED | Bounded Channel drain → batched RowBinary insert; PartCountIT proves end-to-end |
| ClickHouseSpanRepository | trace_id_ts + spans | two-step `{tid:String}` lookup | ✓ WIRED | EXPLAIN granule pruning proven by IT |
| ClickHouseSpanRepository | payloads | `{tid}`/`{sid}` point lookup | ✓ WIRED | Round-trip + absent→null proven by IT |
| MV → agent_runs/trace_id_ts | plain DateTime key cols | min/max(Timestamp) fed key | ✓ WIRED | Code 549 closed; single-batch reconciliation correct (multi-batch → Phase 4) |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| --- | --- | --- | --- |
| Full backend suite vs real ClickHouse (verifier re-ran, forced fresh) | `./gradlew :backend:test --rerun-tasks` (DOCKER_HOST=desktop-linux) | BUILD SUCCESSFUL 28s | ✓ PASS |
| JUnit XML totals (all 13 classes) | parsed TEST-*.xml | tests=65 skipped=0 failures=0 errors=0 | ✓ PASS |
| Storage ITs ran (not skipped) | per-class XML | SchemaIT=4, RepositoryIT=6, PartCountIT=1, all skipped=0 | ✓ PASS |
| `: Unit` un-skip fix holds | grep IT @Test signatures | all `(): Unit =`; no bare `() =` | ✓ PASS |
| No disabled/ignored tests | grep `@Disabled\|@Ignore` | none | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
| --- | --- | --- | --- | --- |
| STOR-01 | 02-01..02-04 | Schema stores spans/traces/agent_runs with typed first-class agent columns | ✓ SATISFIED | 001_spans.sql typed cols + agent_runs rollup; `typedAgentColumnsRoundTrip` IT observed green |
| STOR-02 | 02-02, 02-03 | Trace lookup by trace_id served by MV (no full table scan) | ✓ SATISFIED | 003 MV + two-step getTraceById; `traceByIdPrunesGranules` EXPLAIN indexes=1 observed green |
| STOR-03 | 02-03, 02-04 | Span input/output payloads stored, enabling replay + detail panel | ✓ SATISFIED | 002 payloads table + getPayload + 256KB truncation; payload round-trip ITs observed green |

No orphaned requirements: REQUIREMENTS.md maps exactly STOR-01/02/03 to Phase 2; all three claimed across plan frontmatters and satisfied with observed integration evidence. REQUIREMENTS.md already marks all three `[x] Complete`.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
| --- | --- | --- | --- | --- |
| (storage main/migrations) | — | No TODO/FIXME/XXX/HACK/PLACEHOLDER | — | Clean |
| (tests) | — | No `@Disabled`/`@Ignore`; no silently-skipped `@Test` | — | Clean (0 skipped across 65) |
| `SpanRepositoryIT.kt` | 71-72 | `got.costUsd!!`/`contextSaturationPct!!` (REVIEW IN-01) | ℹ️ Info | Test-only, pre-existing; CLAUDE.md `!!` ban scoped to production. Prefer `shouldNotBeNull()`. Not a blocker. |
| `MigrationRunner.kt` / split test | 135 / 61 | "line 13 of 001_spans.sql" prose (REVIEW IN-02) | ℹ️ Info | Behavioral assertions are robust; line-number prose may rot. Cosmetic. |
| `MigrationRunner.splitStatements` | 142-149 | No `/* */` block-comment / in-literal `--` handling (REVIEW WR-03) | ℹ️ Info (latent) | No current migration triggers it; documented assumption. Tighten before adding migrations with string-literal `--` or block comments. |

### Assessment of REVIEW WR-01/WR-02 (advisory — does NOT block Phase 2)

WR-01/WR-02 correctly observe that `agent_runs`/`trace_id_ts` use **plain** (non-aggregate) DateTime columns for PARTITION/ORDER BY/TTL keys, and that AggregatingMergeTree keeps an *arbitrary* row's value for plain columns on merge — so a single trace whose spans cross a calendar-day boundary or arrive across multiple write batches can split into two partition rows that `FINAL` won't reconcile, or have a non-deterministic TTL anchor. The schema comments' claim that "the last-merged plain value equals the trace's min/max timestamp" is technically incorrect about ClickHouse merge semantics.

**Verdict: correctly Phase-4-scoped; does not undermine any Phase-2 must-have.**
- None of SC1-SC4 depends on multi-batch `agent_runs` reconciliation. SC1 (typed cols), SC2 (trace-by-id via trace_id_ts), SC3 (payloads), SC4 (bounded parts) are all proven green and are orthogonal to multi-batch run-rollup determinism.
- Decision **D-07** (DISCUSSION-LOG line 47, ROADMAP line 74) explicitly defers late/out-of-order/multi-batch span handling to **Phase 4**, and the AggregatingMergeTree + SimpleAggregateFunction engine was chosen precisely so that fix lands **without a table rewrite** — which satisfies the Phase-2 contract ("every fix-later = table-rewrite decision pinned here"). Multi-batch reconciliation is a query/merge fix, not an unrecoverable schema decision.
- Residual advisory (for Phase 4): correct the over-stated schema comments to say the plain-column merge value is non-deterministic, and add an IT writing one trace in two batches / across a day boundary asserting a single fully-aggregated `listRuns` row + non-premature TTL.

### Gaps Summary

No blocking gaps. The single UAT blocker and its secondary defects are genuinely closed:
- **Code 62 SYNTAX_ERROR** — fixed: `splitStatements` strips `--` comments before splitting on `;`; Docker-free `MigrationRunnerSplitTest` regression-guards it (the test that would have caught the incident).
- **Code 549 DATA_TYPE_CANNOT_BE_USED_IN_KEY** — fixed: plain DateTime key/PARTITION/TTL columns in both MV target tables.
- **JUnit silently-skipped expression-body `@Test`** (the masking root cause that fooled earlier verification) — fixed: explicit `(): Unit =`; verifier independently confirms **0 skipped across all 65 tests**.

All four success criteria are now backed by **observed-green Testcontainers integration evidence the verifier re-ran independently** (65/0/0 with `--rerun-tasks` against real ClickHouse on `desktop-linux`), not structural/grep substitution. WR-01/WR-02 are legitimate advisories correctly deferred to Phase 4 by Decision D-07 and do not block the phase goal.

---

_Verified: 2026-06-22_
_Verifier: Claude (gsd-verifier)_
