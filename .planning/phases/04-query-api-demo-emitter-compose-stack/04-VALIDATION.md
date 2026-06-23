---
phase: 04
slug: query-api-demo-emitter-compose-stack
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-06-23
---

# Phase 04 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from `04-RESEARCH.md` § Validation Architecture (SC1–SC5).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter 5.14.4) + Kotest assertions (6.1.11); Testcontainers (`org.testcontainers:clickhouse` 1.21.4) for storage-backed proofs |
| **Config file** | `backend/build.gradle.kts` (test task); new `demo-emitter/build.gradle.kts` |
| **Quick run command** | `./gradlew :backend:test` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30–90 seconds (unit); Testcontainers ITs Docker-gated (same Ryuk/launch caveat as Phases 2–3) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :backend:test` (unit tests only — fast)
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** ~90 seconds (Docker-free unit + assembly tests)

---

## Per-Task Verification Map

> Populated by the planner per task. SC→test mapping from RESEARCH.md Validation Architecture:

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | API-01 / SC1 | — | Orphaned/out-of-order spans returned, never dropped | unit | `./gradlew :backend:test --tests '*DagAssembler*'` | ❌ W0 | ⬜ pending |
| 04-01-02 | 01 | 1 | API-02 / SC2 | — | Subtree cost roll-up = sum of descendant costs | unit | `./gradlew :backend:test --tests '*CostRollup*'` | ❌ W0 | ⬜ pending |
| 04-02-01 | 02 | 2 | API-03 / SC3 | — | List newest-first, offset/limit honored, outcome derived | unit | `./gradlew :backend:test --tests '*RunList*'` | ❌ W0 | ⬜ pending |
| 04-02-02 | 02 | 2 | API-04 / SC3 | — | Saturation timeline series returned per run | unit | `./gradlew :backend:test --tests '*Saturation*'` | ❌ W0 | ⬜ pending |
| 04-03-01 | 03 | 2 | TOOL-01 / SC4 | — | Demo emitter emits N seeded runs exercising full convention | unit/manual | `./gradlew :demo-emitter:test` | ❌ W0 | ⬜ pending |
| 04-04-01 | 04 | 3 | DIST-01 / SC5 | — | Cold `docker compose up` ingests demo traces end-to-end | manual/CI | `docker compose up` smoke (Docker host required) | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky · Task IDs/rows are provisional — planner finalizes.*

---

## Wave 0 Requirements

- [ ] `backend/src/test/kotlin/.../query/DagAssemblerTest.kt` — SC1 orphan/out-of-order, SC2 roll-up stubs (API-01/02)
- [ ] `backend/src/test/kotlin/.../query/RunListQueryTest.kt` — SC3 list paging + outcome derivation (API-03)
- [ ] `backend/src/test/kotlin/.../query/SaturationQueryTest.kt` — SC3 saturation series (API-04)
- [ ] `demo-emitter/src/test/kotlin/.../DemoEmitterTest.kt` — SC4 seeded determinism (TOOL-01)
- [ ] JSON test stack: add `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`, `kotlinx-serialization-json` + serialization plugin (net-new per RESEARCH.md)

*Planner: confirm exact test paths/package against the discovered `query/` package layout.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Cold-start end-to-end Compose ingest (SC5) | DIST-01 | Requires a real Linux Docker host (Ryuk/container-launch limits block local Testcontainers; same debt as Phases 2–3) | `docker compose up`, wait for healthy, `curl :8080/api/runs` returns the seeded runs |
| Demo realism / saturation depth (SC4) | TOOL-01 | Subjective realism threshold; partly observed via emitted-then-queried roundtrip | Run emitter against running tracer, query DAG + saturation, confirm skills/subagents/costs present |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
