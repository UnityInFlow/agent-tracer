---
phase: 04-query-api-demo-emitter-compose-stack
verified: 2026-06-24T08:05:00Z
status: passed
score: 5/5 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: none
---

# Phase 4: Query API, Demo Emitter & Compose Stack Verification Report

**Phase Goal:** Expose the agent-trace query surface end-to-end — a JSON Query API (run list with filters, per-run DAG with cost roll-ups, saturation timeline) over an isolated HTTP server, a seeded demo-emitter that produces realistic full-convention agent runs, and a Docker Compose all-in-one that cold-starts ClickHouse → tracer → demo seeder and serves the seeded runs. (API-01..04, TOOL-01, DIST-01)
**Verified:** 2026-06-24T08:05:00Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (Roadmap Success Criteria)

| # | Truth (Roadmap SC) | Status | Evidence |
| --- | --- | --- | --- |
| 1 | API returns any agent run as an assembled DAG, tolerant of late/out-of-order/orphaned spans | ✓ VERIFIED | `DagAssembler.kt` builds nodes/edges via `associateBy` (order-independent); orphans reparented to `SYNTHETIC_ROOT_ID` (`else -> SYNTHETIC_ROOT_ID`), never dropped; mounted at `GET /api/runs/{id}/dag` (`QueryApi.kt:71-88`). `DagAssemblerTest` 4 tests pass, 0 skipped. |
| 2 | API returns subagent hierarchy with subtree cost roll-ups | ✓ VERIFIED | `rollUpCosts()` does a cycle-guarded post-order walk (`onPath`/`memo`), `subtreeCostUsd = own + descendants`; `group = operation == "INVOKE_AGENT"` marks agent/subagent nodes (`DagAssembler.kt:60,101-131`). Covered by DagAssemblerTest SC2. |
| 3 | API returns filtered run list (agent, model, cost range, outcome, date range) + saturation timeline | ✓ VERIFIED | `RunListMapper.toFilter` maps all 5 dims with NaN/Inf + ISO-8601 validation and limit≤500 clamp; `ClickHouseSpanRepository.listRuns` binds all 5 as `{name:Type}` placeholders, `ORDER BY StartTime DESC, TraceId DESC LIMIT {limit:UInt32} OFFSET {offset:UInt32}`. `SaturationAssembler.assemble` returns non-null pts sorted by ts; route `GET /api/runs/{id}/saturation`. RunListMapperTest 5 + SaturationAssemblerTest 2 pass. |
| 4 | Demo emitter generates realistic synthetic runs exercising full convention | ✓ VERIFIED | `SeededRunFactory.kt`: invoke_agent root → plan → ≥3 skills → ≥2 nested subagents → tools; rising `RisingSaturation`; ALL keys from `GenAiAttributes`/`UnityInFlowAttributes` (no literals). `DemoEmitter.main` exports over `OtlpHttpSpanExporter`, bounded flush, exits. SeededRunFactoryTest 4 pass (determinism). |
| 5 | `docker compose up` cold-starts tracer + ClickHouse, ingests demo traces, serves API queries | ✓ VERIFIED | `docker/compose.yaml` 3-service health-gated chain (CH :26.3 → tracer → one-shot seeder `restart:"no"`). Cold-start OBSERVED GREEN on real Docker (Docker Desktop 28.5.1) this session via `bash scripts/compose-smoke.sh`: `GET :8080/api/runs` returned non-empty (planner-agent/gemini-2.5-pro/PARTIAL/costUsd 46.513/spanCount 19/maxSaturation 100.0), clean `down -v`. Recorded in 04-05-SUMMARY.md + environment context. |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
| --- | --- | --- | --- |
| `backend/.../query/DagAssembler.kt` | Orphan-tolerant DAG + subtree cost roll-up | ✓ VERIFIED | 137 lines; assemble() + cycle-guarded rollUpCosts(); wired into QueryApi |
| `backend/.../query/SaturationAssembler.kt` | Timeline projection | ✓ VERIFIED | non-null filter + sortedBy timestamp; wired into QueryApi |
| `backend/.../query/RunListMapper.kt` | param→RunFilter + paging + outcome | ✓ VERIFIED | 5-dim filter, NaN/Inf reject, ISO-8601, MAX_LIMIT=500 |
| `backend/.../query/{DagResponse,SaturationResponse,RunListResponse}.kt` | Immutable @Serializable DTOs | ✓ VERIFIED | All @Serializable; grep for `var ` returns none |
| `backend/.../query/QueryApi.kt` | Route.queryApi mounting 3 routes | ✓ VERIFIED | `/api/runs`, `/api/runs/{id}/dag`, `/api/runs/{id}/saturation`; 400/404/500 hygiene |
| `backend/.../app/TracerApplication.kt` | :8080 query server + /healthz + drain | ✓ VERIFIED | Isolated `embeddedServer(Netty, 8080)`, ContentNegotiation only here, queryServer in RunningReceivers + shutdown() |
| `backend/.../storage/{SpanRepository,ClickHouseSpanRepository}.kt` | listRuns(filter,limit,offset) newest-first paged | ✓ VERIFIED | 3-arg sig, DEFAULT_LIMIT=50, placeholder-bound paging |
| `demo-emitter/.../SeededRunFactory.kt` | Seeded full-convention generator | ✓ VERIFIED | Deterministic, semconv-keyed, rising saturation |
| `demo-emitter/.../DemoEmitter.kt` | One-shot OTLP/HTTP exporter main | ✓ VERIFIED | OtlpHttpSpanExporter, bounded flush+exit |
| `settings.gradle.kts` | demo-emitter module included | ✓ VERIFIED | `include(... "demo-emitter" ...)` |
| `backend/build.gradle.kts` | JSON stack + application plugin | ✓ VERIFIED | 3 JSON deps + serialization plugin + application{mainClass=TracerApplicationKt}; installDist task present |
| `docker/compose.yaml` | 3-service cold-start stack | ✓ VERIFIED | health gating, one-shot seeder, secret-free env |
| `docker/tracer.Dockerfile` | temurin:21-jre + installDist + healthcheck | ✓ VERIFIED | multi-stage installDist, /dev/tcp probe |
| `docker/emitter.Dockerfile` | temurin:21-jre one-shot emitter | ✓ VERIFIED | :demo-emitter:installDist, CMD bin/demo-emitter |
| `docker/healthcheck.sh` | curl/wget-free liveness probe | ✓ VERIFIED | bash /dev/tcp GET /healthz, asserts 200 |
| `scripts/compose-smoke.sh` | cold up → poll /api/runs until non-empty | ✓ VERIFIED | up --build, poll, SC5 assert, down -v trap |

### Key Link Verification

| From | To | Via | Status | Details |
| --- | --- | --- | --- | --- |
| `backend/build.gradle.kts` | `libs.versions.toml` | libs.* aliases for 3 JSON deps + serialization plugin | ✓ WIRED | All aliases resolve; `:backend:compileKotlin` green |
| `ClickHouseSpanRepository.listRuns` | `{limit:UInt32}/{offset:UInt32}` | params binding | ✓ WIRED | `params["limit"]`/`["offset"]`, no interpolation |
| `Route.queryApi` handlers | DagAssembler/SaturationAssembler/RunListMapper + repository | getTraceById/listRuns → assemble/timeline → respond | ✓ WIRED | All three assemblers instantiated and invoked |
| `RunningReceivers.shutdown()` | `queryServer.stop(...)` | drain alongside ktor.stop | ✓ WIRED | `queryServer.stop(...)` at TracerApplication.kt:209 |
| `SeededRunFactory` | agent-semconv constants | AttributeKey.*(constant) | ✓ WIRED | `UnityInFlowAttributes.CONTEXT_SATURATION_PCT` + GenAiAttributes used; no literals |
| `DemoEmitter.main` | OTLP receiver :4318 | OtlpHttpSpanExporter.setEndpoint | ✓ WIRED | endpoint default `.../v1/traces`, Compose sets tracer:4318 |
| compose tracer | clickhouse | depends_on service_healthy | ✓ WIRED | condition: service_healthy |
| compose demo-emitter | tracer:4318 | OTLP_ENDPOINT + depends_on tracer healthy | ✓ WIRED | http://tracer:4318/v1/traces |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| --- | --- | --- | --- |
| Query-layer unit tests pass | `./gradlew :backend:test --tests 'query.*'` | Dag=4, Saturation=2, RunList=5, QueryApi=4 — 15 tests, 0 failures, 0 skipped | ✓ PASS |
| Demo determinism test passes | `./gradlew :demo-emitter:test` | SeededRunFactoryTest=4, 0 failures, 0 skipped | ✓ PASS |
| Backend installDist exists (Compose path real) | `./gradlew :backend:tasks --all` | `installDist` present | ✓ PASS |
| Demo installDist + mainClass | `./gradlew :demo-emitter:tasks --all` | `installDist` present; mainClass=DemoEmitterKt | ✓ PASS |
| No debt markers in phase files | `grep -rE 'TBD|FIXME|XXX'` | NO DEBT MARKERS FOUND | ✓ PASS |
| SC5 cold-start (real Docker) | `bash scripts/compose-smoke.sh` | Observed GREEN this session (Docker Desktop 28.5.1): non-empty /api/runs, clean down -v | ✓ PASS |

Note: The 6 `*IT` container-launching integration tests fail on `ContainerLaunchException` (Testcontainers/Ryuk sandbox limitation, documented precedent [02-05]/[03-04]) — infrastructure, NOT code defects; correctly excluded from the goal judgment. The real-Docker `docker compose up` round-trip (which bypasses Ryuk) was observed green, closing the deferred Phase-2/3 ClickHouse IT carry-forward.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
| --- | --- | --- | --- | --- |
| API-01 | 04-02, 04-04 | Run as assembled DAG (query-time parent-child tree) | ✓ SATISFIED | DagAssembler + GET /api/runs/{id}/dag |
| API-02 | 04-02, 04-04 | Subagent hierarchy with subtree cost roll-ups | ✓ SATISFIED | INVOKE_AGENT group nodes + rollUpCosts |
| API-03 | 04-01, 04-02, 04-04 | Filter runs by agent/model/cost/outcome/date | ✓ SATISFIED | RunListMapper + listRuns 5-dim placeholder filter |
| API-04 | 04-02, 04-04 | Context-saturation timeline series | ✓ SATISFIED | SaturationAssembler + GET /api/runs/{id}/saturation |
| TOOL-01 | 04-03 | Demo emitter via OTLP exercising full convention | ✓ SATISFIED | SeededRunFactory + DemoEmitter.main, semconv keys |
| DIST-01 | 04-04, 04-05 | `docker compose up` cold-start end-to-end | ✓ SATISFIED | compose.yaml + observed-green cold-start smoke |

All 6 declared requirement IDs accounted for; all map to Phase 4 in REQUIREMENTS.md traceability table. No orphaned requirements.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
| --- | --- | --- | --- | --- |
| (none) | — | No TBD/FIXME/XXX in phase-modified source | ℹ️ Info | Clean — completion is auditable |

The 04-REVIEW.md documented 0 blockers, 7 warnings (WR-01..07: ms-precision truncation, missing costMin≤costMax cross-validation, CancellationException swallow, silent limit fallback, toResponse default echo, healthcheck read-timeout, empty-password CH user). All are robustness/hardening concerns that do not block any must-have or success criterion; none surfaced as a goal-blocking defect.

### Human Verification Required

None outstanding. The only human-verify checkpoint in the phase (SC5 cold-start on real Docker) was executed and observed GREEN this session (04-05-SUMMARY.md "Checkpoint Result — SC5 cold-start smoke: VERIFIED PASS"; corroborated by environment context). No `<human-check>` blocks remain in any PLAN.

### Gaps Summary

No gaps. All 5 roadmap success criteria are observably achieved in the codebase: the DAG/filter/timeline query API is implemented and wired over an isolated :8080 JSON server with ContentNegotiation kept off the protobuf OTLP receiver; the seeded full-convention demo-emitter is a runnable module producing deterministic OTLP runs (the sole saturation producer); and the Docker Compose all-in-one cold-starts the full chain and was observed serving non-empty seeded runs on real Docker. All 15 query-layer unit tests plus the demo determinism test pass (0 skipped). The two structural deviations from PLAN frontmatter (DTOs live in `query/` not `query/dto/`; SaturationAssembler exposes `assemble()` not `timeline()`) are cosmetic — the wiring, behavior, and tests are all correct and green.

---

_Verified: 2026-06-24T08:05:00Z_
_Verifier: Claude (gsd-verifier)_
