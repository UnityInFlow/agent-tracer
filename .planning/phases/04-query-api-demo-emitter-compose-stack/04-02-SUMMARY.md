---
phase: 04-query-api-demo-emitter-compose-stack
plan: 02
subsystem: backend
tags: [query, dag, saturation, run-list, dto, serialization, validation]
requires:
  - "Plan 04-01: kotlinx-serialization-json + serialization compiler plugin on backend classpath"
  - "Plan 04-01: Wave-0 RED stubs (DagAssemblerTest, SaturationAssemblerTest, RunListMapperTest) defining the symbol contracts"
  - "storage: SpanRow, AgentRunRow, RunFilter, SpanRepository.DEFAULT_LIMIT=50"
provides:
  - "query.DagAssembler.assemble(List<SpanRow>): DagResponse — orphan-tolerant flat DAG + cycle-guarded subtree cost roll-up"
  - "query.SaturationAssembler.assemble(List<SpanRow>): SaturationResponse — emitter-gated non-null saturation timeline"
  - "query.RunListMapper: toFilter / limitOf / offsetOf / toSummary / toResponse with NaN/Inf + paging + ISO-8601 validation"
  - "query @Serializable DTOs: DagResponse/DagNode/DagEdge, SaturationResponse/SaturationPoint, RunListResponse/RunSummary, InstantSerializer"
affects:
  - "Plan 04 (Route.queryApi): mounts these three transforms over HTTP; QueryApiTest depends on them"
tech-stack:
  added: []
  patterns:
    - "Pure in-JVM query transforms (no SQL, no coroutines) kept in query/, storage stays sole SQL holder"
    - "Instant-typed @Serializable DTO fields via a custom epoch-millis KSerializer (InstantSerializer)"
    - "HTTP-param validation seam: NaN/Inf rejection + limit clamp + offset floor BEFORE the repository"
key-files:
  created:
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/DagResponse.kt"
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/SaturationResponse.kt"
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/RunListResponse.kt"
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/InstantSerializer.kt"
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/DagAssembler.kt"
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/SaturationAssembler.kt"
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/query/RunListMapper.kt"
  modified: []
decisions:
  - "DTOs live in the query package (NOT query/dto) — the Plan-01 Wave-0 stubs reference DagResponse/SaturationResponse/RunSummary etc. with NO dto import, so the binding test contract requires same-package placement"
  - "Instant DTO fields kept as java.time.Instant (test compares against Instant directly) + a custom InstantSerializer emitting epoch-millis on the JSON wire — reconciles the @Serializable requirement with the test contract"
  - "RunListMapper exposes limitOf/offsetOf (not a single paging()) per the Wave-0 test method names; clamp 1..500, floor offset at 0"
  - "SaturationAssembler.assemble (not timeline) per the test method name"
metrics:
  duration: ~20 min
  tasks: 2
  files: 7
  completed: 2026-06-23
---

# Phase 4 Plan 02: Query Transform Layer (DAG assembler + saturation timeline + run-list mapper) Summary

Built the only net-new query *logic* in Phase 4: the flat, orphan-tolerant `DagAssembler` with cycle-guarded subtree cost roll-ups, the emitter-gated `SaturationAssembler` timeline projection, the `RunListMapper` query-param→`RunFilter` mapper with NaN/Inf/paging/ISO-8601 validation, and the seven immutable `@Serializable` DTOs they return. All three Plan-01 Wave-0 RED stubs (`DagAssemblerTest`, `SaturationAssemblerTest`, `RunListMapperTest`) now pass. No SQL — storage remains the sole SQL holder. Plan 04 mounts these over HTTP.

## What Was Built

### Task 1 — DTOs + DagAssembler (`1c9089b`, finalized in `069fa0a`)
- `DagResponse`/`DagNode`/`DagEdge`, `SaturationResponse`/`SaturationPoint`, `RunListResponse`/`RunSummary` — all immutable `@Serializable data class` (every property `val`).
- `InstantSerializer` (epoch-millis `KSerializer<Instant>`) so the `Instant`-typed DTO fields stay `@Serializable` for Plan 04's JSON boundary.
- `DagAssembler.assemble(List<SpanRow>): DagResponse` — `associateBy { spanId }`, orphan reparent under `SYNTHETIC_ROOT_ID` (`parentSpanId` non-empty but absent → `orphaned = true`, never dropped, SC1), `parentSpanId == "" → parentId = null`, edges per non-null parent, `group = operation.equals("INVOKE_AGENT")`, `kind = operation.ifEmpty { "unknown" }`.
- Private `rollUpCosts`: cycle-guarded post-order DFS (visited `onPath` set + memo) — `subtreeCostUsd = own (null→0) + descendants` (SC2); orphan subtree costs roll into the synthetic root; a cyclic graph terminates without StackOverflow.

### Task 2 — SaturationAssembler + RunListMapper (`069fa0a`)
- `SaturationAssembler.assemble`: `mapNotNull { s -> s.contextSaturationPct?.let { SaturationPoint(s.timestamp, it) } }.sortedBy { it.timestamp }` — only non-null points, ascending, all-null → empty (SC3b/D-04). No `!!`.
- `RunListMapper.toFilter`: present-only param mapping; `costMin/costMax` via `toDoubleOrNull()` + `require(isFinite())` → `IllegalArgumentException` on NaN/Infinity (T-04-03); `dateFrom/dateTo` via `Instant.parse` → `IllegalArgumentException` on bad ISO-8601.
- `RunListMapper.limitOf`: default `SpanRepository.DEFAULT_LIMIT` (50), `coerceIn(1, MAX_LIMIT=500)` (T-04-04 DoS); `offsetOf`: default 0, `coerceAtLeast(0)`.
- `RunListMapper.toSummary`/`toResponse`: carries `AgentRunRow.outcome` (the `agent_runs.anyLast` list-scale proxy) into `RunSummary.outcome`; the proxy-vs-authoritative-`/dag`-root-status distinction is documented in `RunSummary.outcome` KDoc (RESEARCH A3).

## Verification Evidence

- `./gradlew :backend:test --tests '*DagAssembler*' --tests '*Saturation*' --tests '*RunListMapper*'` — **BUILD SUCCESSFUL** (all three Wave-0 stubs flipped RED→GREEN). Run with the Plan-04 `QueryApiTest` temporarily held aside (see below); QueryApiTest restored byte-identical afterward (`git status` shows no diff in the test dir).
- `./gradlew :backend:lintKotlinMain` — BUILD SUCCESSFUL.
- `./gradlew :backend:compileKotlin` — BUILD SUCCESSFUL.
- `grep -rn 'var ' .../query/*.kt` → none (immutable DTOs + no `var` anywhere in `query/`, CLAUDE.md / Pitfall 2).
- `grep -rn '!!' .../query/*.kt` → only KDoc prose ("No `!!`"), no operator usage.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] DTOs relocated from `query/dto/` to `query/` to match the binding Wave-0 test contract**
- **Found during:** Task 1 verification (test compilation).
- **Issue:** The plan's `artifacts_this_plan_produces` text placed the DTOs in a `query/dto/` subpackage, but the Plan-01 Wave-0 stubs — which the plan declares authoritative ("its assertions define the contract") — reference `DagResponse`, `DagNode`, `DagEdge`, `SaturationResponse`, `SaturationPoint`, `RunSummary`, `RunListResponse` with **no `dto` import**, from `package io.github.unityinflow.agenttracer.query`. A `query/dto/` placement does not compile against the stubs.
- **Fix:** Created all DTOs (and `InstantSerializer`) directly in the `query` package; removed the `.dto` imports from the three transform classes. Git records this as renames `query/dto/*.kt → query/*.kt`.
- **Files:** `DagResponse.kt`, `SaturationResponse.kt`, `RunListResponse.kt`, `InstantSerializer.kt` (relocated); `DagAssembler.kt` (import removed).
- **Commit:** `069fa0a` (relocation landed with Task 2 to avoid an intermediate non-compiling state).

**2. [Rule 3 - Blocking] DTO field/method shapes aligned to the test contract over the plan prose**
- `SaturationPoint` exposes `timestamp: Instant` (+ `InstantSerializer`) rather than `timestampMillis: Long` — the test asserts `points.map { it.timestamp } shouldBe listOf(BASE_TIME, …)` against `Instant`s.
- `SaturationAssembler.assemble` / `DagAssembler.assemble` are **classes** instantiated with `()` (the tests do `DagAssembler().assemble(...)`), method named `assemble` (not `timeline`).
- `RunListMapper` exposes `limitOf`/`offsetOf`/`toResponse` (test method names) rather than a single `paging()`.
- `DagNode` carries `isSyntheticRoot` and a non-null `subtreeCostUsd: Double` (the test reads `it.isSyntheticRoot` and `rootNode.subtreeCostUsd shouldBeExactly 0.35`).
- No user decision required — these are the test-defined contract, which the plan itself names authoritative.

**3. [Rule 1 / CLAUDE.md] Two local `var`s refactored to `val`**
- `var hasOrphan` → derived `val` from `resolvedParent.values.any { … }`; `var total` in the roll-up → `val` via `(ownCost[id] ?: 0.0) + (children[id]?.sumOf { … } ?: 0.0)`. CLAUDE.md forbids `var`.

## Integration-Test / Sandbox Note

This plan's logic is pure-JVM (no Testcontainers, no ClickHouse) — its unit tests actually run and pass. No integration tests were touched, so the pre-existing Ryuk/Colima container-launch debt from Phases 2/3 is unchanged and carried forward unmodified.

## Cross-Plan Test-Compilation Note (not a defect)

`backend/src/test/.../query/QueryApiTest.kt` is a **Plan-04** Wave-0 stub that references `Route.queryApi`, a symbol Plan 04 creates. Because the Kotlin test source set is a single compilation unit, that stub blocks a full `:backend:test` compile until Plan 04 lands — exactly as Plan 01's SUMMARY records ("Plan 04 … QueryApiTest names the route"). To verify *this* plan's three test groups in GREEN without prematurely implementing Plan 04, `QueryApiTest.kt` was moved aside for the test run and restored byte-identical immediately after (verified: no diff in the test directory). This plan implements no Plan-04 symbol.

## Threat Surface

No new security surface beyond the plan's `<threat_model>`. The mitigations it assigned are implemented: T-04-03 (NaN/Inf cost reject + ISO-8601 date parse in `RunListMapper`), T-04-04 (`limit` clamp ≤ 500 / `offset` floor 0), T-04-05 (single-pass assembler + cycle-guarded roll-up). No new packages (T-04-SC).

## Self-Check: PASSED

- Created files: DagResponse.kt, SaturationResponse.kt, RunListResponse.kt, InstantSerializer.kt, DagAssembler.kt, SaturationAssembler.kt, RunListMapper.kt — all FOUND under `backend/src/main/.../query/`.
- Commits 1c9089b, 069fa0a — both FOUND in git log.
