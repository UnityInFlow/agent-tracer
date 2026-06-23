---
phase: 04-query-api-demo-emitter-compose-stack
plan: 01
subsystem: backend
tags: [build, serialization, packaging, storage, pagination, wave-0-stubs]
requires:
  - "backend module (Phase 2/3): SpanRepository, ClickHouseSpanRepository, RunFilter, AgentRunRow, SpanRow, app/TracerApplication.kt"
  - "gradle/libs.versions.toml version catalog"
provides:
  - "kotlinx-serialization-json + ktor-server-content-negotiation + ktor-serialization-kotlinx-json on the backend classpath"
  - "kotlin-serialization compiler plugin applied to backend"
  - "application plugin on backend (installDist/distZip/run) for the Compose tracer image"
  - "SpanRepository.listRuns(filter, limit, offset) newest-first paginated signature + DEFAULT_LIMIT=50 const"
  - "ClickHouseSpanRepository.listRuns ORDER BY StartTime DESC, TraceId DESC LIMIT {limit:UInt32} OFFSET {offset:UInt32}"
  - "Wave-0 RED test stubs: DagAssemblerTest, SaturationAssemblerTest, RunListMapperTest, QueryApiTest"
affects:
  - "Plan 02 (DagAssembler, SaturationAssembler, RunListMapper + DTOs) — stubs name the symbols to create"
  - "Plan 04 (Route.queryApi + ContentNegotiation install on :8080) — QueryApiTest names the route"
  - "Plan 05 (Compose tracer image) — installDist runnable jar"
tech-stack:
  added:
    - "kotlinx-serialization-json 1.11.0"
    - "ktor-server-content-negotiation 3.5.0"
    - "ktor-serialization-kotlinx-json 3.5.0"
    - "org.jetbrains.kotlin.plugin.serialization (Kotlin 2.4.0)"
    - "Gradle application plugin (backend)"
  patterns:
    - "Catalog-first dependency declaration (libs.* aliases)"
    - "SQL placeholder binding for limit/offset (T-02-06) — never interpolated"
    - "Wave-0 RED scaffold: stubs reference not-yet-existing symbols so they fail to compile"
key-files:
  created:
    - "backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/DagAssemblerTest.kt"
    - "backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/SaturationAssemblerTest.kt"
    - "backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/RunListMapperTest.kt"
    - "backend/src/test/kotlin/io/github/unityinflow/agenttracer/query/QueryApiTest.kt"
  modified:
    - "gradle/libs.versions.toml"
    - "backend/build.gradle.kts"
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepository.kt"
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSpanRepository.kt"
    - "backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepositoryIT.kt"
decisions:
  - "DEFAULT_LIMIT=50 lives on SpanRepository companion — single source of truth shared by interface KDoc default and the storage tests"
  - "ContentNegotiation NOT installed on any Application in this plan — deferred to Plan 04's :8080 server; this plan only puts the deps + compiler plugin on the classpath"
  - "Repository binds whatever limit/offset it is given; clamping (default/max-cap ≤500, offset≥0) is the route mapper's job (Plan 02), not storage's (T-04-02 documented hand-off)"
metrics:
  duration: ~8 min
  tasks: 3
  files: 7
  completed: 2026-06-23
---

# Phase 4 Plan 01: Query-API Foundation (JSON stack + paginated listRuns + Wave-0 stubs) Summary

Laid the Phase-4 backend foundation: the net-new JSON serialization stack and `application` packaging plugin on `backend`, the newest-first offset/limit `listRuns` extension (API-03/D-05) with bound `{limit:UInt32}`/`{offset:UInt32}` placeholders, and four RED Wave-0 test stubs that pin the exact query-package symbols Plans 02/04 must produce.

## What Was Built

### Task 1 — JSON serialization stack + application packaging plugin (`28d21ba`)
- `gradle/libs.versions.toml`: added `serialization = "1.11.0"`; libraries `kotlinx-serialization-json`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`; plugin alias `kotlin-serialization`.
- `backend/build.gradle.kts`: applied `alias(libs.plugins.kotlin.serialization)` + `application`; added the 3 JSON `implementation` deps; `application { mainClass.set("io.github.unityinflow.agenttracer.app.TracerApplicationKt") }`.
- Verified: `:backend:compileKotlin` succeeds with the serialization plugin; the 3 JSON libs are on `runtimeClasspath`; the `application` plugin contributes `installDist` (proves the runnable-jar packaging exists for Plan 05).
- ContentNegotiation is deliberately NOT installed on any `Application` here (deferred to Plan 04).

### Task 2 — Paginated newest-first listRuns, API-03/D-05 (`1fa124e`)
- `SpanRepository.listRuns` → 3-arg `(filter, limit, offset): Result<List<AgentRunRow>>`; added `companion object { const val DEFAULT_LIMIT = 50 }` as the single source of truth; KDoc states newest-first + bound-placeholder + clamping-deferred contract.
- `ClickHouseSpanRepository.listRuns`: kept the non-null predicate/params build verbatim; flipped `ORDER BY StartTime, TraceId` → `ORDER BY StartTime DESC, TraceId DESC` and appended `LIMIT {limit:UInt32} OFFSET {offset:UInt32}`; bound `params["limit"]`/`params["offset"]` exactly like the existing predicates — zero interpolation (T-02-06 / T-04-01 mitigation).
- Updated both real 1-arg call sites in `SpanRepositoryIT.kt` (lines 147, 190) to the 3-arg form with `SpanRepository.DEFAULT_LIMIT, 0`. Re-grep confirms no stale 1-arg call remains.
- Verified: greps for `ORDER BY StartTime DESC, TraceId DESC` and `limit:UInt32` pass; `:backend:compileKotlin` and `:backend:compileTestKotlin` both compile.

### Task 3 — Wave-0 RED query-package stubs (`e2f53f3`)
Four JUnit 5 + Kotest test files under `backend/src/test/.../query/`, each a block-body `@Test` returning Unit (Pitfall 5 — no expression-body silent skip), none `@Disabled`:
- `DagAssemblerTest` — SC1 (orphaned span reparented to a synthetic root, never dropped; late/out-of-order spans placed by membership not arrival) + SC2 (subtree cost roll-up = own + descendants, cycle-guarded). References `DagAssembler`, `DagResponse`, `DagNode`, `DagEdge`.
- `SaturationAssemblerTest` — SC3b (timeline = only non-null `contextSaturationPct` points, sorted by timestamp; all-null → empty). References `SaturationAssembler`, `SaturationResponse`, `SaturationPoint`.
- `RunListMapperTest` — SC3a (query-param → `RunFilter`: NaN/Inf cost rejection, ISO-8601 dates, `limit` default = `DEFAULT_LIMIT` and max-cap ≤ 500, `offset` floored at 0) + outcome derivation. References `RunListMapper`, `RunSummary`, `RunListResponse`.
- `QueryApiTest` — SC3-route over a `mockk<SpanRepository>()` with `ContentNegotiation { json() }` + `queryApi(repo)`: 200 list, 200 known-run dag, 404 unknown run, 400 bad cost param. References `Route.queryApi`.

## Verification Evidence

- `./gradlew :backend:compileKotlin` — BUILD SUCCESSFUL (serialization plugin applied).
- `runtimeClasspath` contains `kotlinx-serialization-json` (grep count 10 > 0).
- `installDist` task present (`./gradlew :backend:tasks --all`).
- `grep "ORDER BY StartTime DESC, TraceId DESC"` and `grep "limit:UInt32"` both match in `ClickHouseSpanRepository.kt`.
- All four query test files exist; none carry a real `@Disabled` annotation (the only "Disabled" text is the KDoc phrase "NOT @Disabled").
- `./gradlew :backend:test --tests '*query*'` fails to compile with `Unresolved reference 'DagAssembler' / 'SaturationAssembler' / 'RunListMapper'` etc. — the intended RED Wave-0 state.

## Expected RED Test-Compilation State (not a defect)

This is a Nyquist Wave-0 scaffold plan: the four stubs reference symbols that Plans 02/04 create, so the **test** source set does not compile until those plans land. This is the designed RED state (`04-VALIDATION.md`: `wave_0_complete` flips during execution when these stubs land RED). The **main** source set compiles cleanly, and `:backend:compileTestKotlin` compiled green at Task 2's checkpoint (before the Task 3 stubs were added). No `@Disabled` was used to hide the failure — the stubs are honestly RED per the Wave-0 contract.

## Deviations from Plan

None — plan executed exactly as written. The two `listRuns` call sites identified in the plan (SpanRepositoryIT.kt lines 147, 190) were the only call sites found by re-grep; both updated.

## Integration-Test / Sandbox Note

No Testcontainers ITs were run or changed by this plan (Task 2 touched `SpanRepositoryIT.kt` only to update the two `listRuns` call signatures; the existing Ryuk/Colima-blocked IT execution debt from Phase 2/3 is unchanged and carried forward unmodified).

## Self-Check: PASSED

- Created files: DagAssemblerTest.kt, SaturationAssemblerTest.kt, RunListMapperTest.kt, QueryApiTest.kt — all FOUND.
- Modified files: libs.versions.toml, backend/build.gradle.kts, SpanRepository.kt, ClickHouseSpanRepository.kt, SpanRepositoryIT.kt — all FOUND.
- Commits 28d21ba, 1fa124e, e2f53f3 — all FOUND in git log.
