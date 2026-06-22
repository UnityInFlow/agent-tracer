---
phase: 2
slug: clickhouse-storage-layer
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-06-21
updated: 2026-06-22
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5.14.4 + Kotest 6.1.11 assertions + Testcontainers (clickhouse 1.21.4) + MockK + kotlinx-coroutines-test |
| **Config file** | `backend/build.gradle.kts` (test task) |
| **Quick run command** | `./gradlew :backend:test --tests "*SpanWriterTest" --tests "*AgentSpanRowMapperTest"` (unit-only, no Docker) |
| **Full suite command** | `./gradlew :backend:test` |
| **Estimated runtime** | ~90 seconds (Testcontainers ClickHouse spin-up dominates; shared container amortizes it across IT classes) |

---

## Sampling Rate

- **After every task commit:** Run quick run command (unit-only, excludes Testcontainers)
- **After every plan wave:** Run full suite command
- **Before `/gsd-verify-work`:** Full suite must be green + `ktlintCheck`
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 01-T1 | 02-01 | 1 | STOR-01/02/03 (infra) | T-02-SC | Official Maven artifacts only; no unverified installs | build | `./gradlew :backend:dependencies --configuration testRuntimeClasspath` | ❌→ W0 | ⬜ pending |
| 01-T2 | 02-01 | 1 | STOR-01/02/03 (infra) | T-02-01 | Container creds via Testcontainers API, no literals; image pinned 26.3 | build | `./gradlew :backend:compileTestKotlin` | ❌→ W0 | ⬜ pending |
| 02-T1 | 02-02 | 2 | STOR-01, STOR-02 | T-02-04 | Typed columns (not Map); Nullable out of ORDER BY; mandatory TTL | integration | `./gradlew :backend:test --tests "*ClickHouseSchemaIT"` | ❌→ 02-T3 | ⬜ pending |
| 02-T2 | 02-02 | 2 | STOR-01, STOR-02 | T-02-02, T-02-03 | Env-var creds (no literal); idempotent CREATE IF NOT EXISTS DDL | unit/build | `./gradlew :backend:compileKotlin` | ❌→ 02-T3 | ⬜ pending |
| 02-T3 | 02-02 | 2 | STOR-02 + migrations | T-02-03, T-02-05 | EXPLAIN granule pruning (no full scan); idempotent re-run | integration | `./gradlew :backend:test --tests "*ClickHouseSchemaIT"` | ❌ W0 | ⬜ pending |
| 03-T1 | 02-03 | 3 | STOR-01/02/03 | T-02-09 | Result<T> seam; rows mirror DDL | build | `./gradlew :backend:compileKotlin` | ❌→ 03-T3 | ⬜ pending |
| 03-T2 | 02-03 | 3 | STOR-01/02/03 | T-02-06 | Parameterized `{name:Type}` queries; no string-concat SQL | unit/build | `./gradlew :backend:compileKotlin` | ❌→ 03-T3 | ⬜ pending |
| 03-T3 | 02-03 | 3 | STOR-01, STOR-02, STOR-03 | T-02-06, T-02-07 | Typed-column + payload round-trip; rollup + dedup (D-07) | integration | `./gradlew :backend:test --tests "*SpanRepositoryIT"` | ❌ W0 | ⬜ pending |
| 04-T1 | 02-04 | 4 | STOR-01, STOR-03 | T-02-12 | 256 KB cap + truncation at write time (D-13) | unit | `./gradlew :backend:test --tests "*AgentSpanRowMapperTest"` | ❌ W0 | ⬜ pending |
| 04-T2 | 02-04 | 4 | STOR-01, STOR-03 (D-08) | T-02-10, T-02-13, T-02-14 | Bounded Channel backpressure (no drop/unbounded); no payload in logs; drain on shutdown | unit/build | `./gradlew :backend:compileKotlin` | ❌→ 04-T3 | ⬜ pending |
| 04-T3 | 02-04 | 4 | SC4 + D-08 | T-02-10, T-02-11 | Flush by count/time; retry-then-backpressure no-drop; bounded `system.parts` | unit + integration | `./gradlew :backend:test --tests "*SpanWriterTest" --tests "*PartCountIT"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky — updated during execution.*

---

## Wave 0 Requirements

- [ ] Testcontainers ClickHouse fixture (`@Container` ClickHouseContainer on `clickhouse/clickhouse-server:26.3`) — shared singleton base for integration tests (`ClickHouseTestSupport`, Plan 02-01 Task 2)
- [ ] `org.testcontainers:clickhouse:1.21.4` + `org.testcontainers:junit-jupiter:1.21.4` + JUnit 5 + Kotest + MockK added to `backend/build.gradle.kts` and `gradle/libs.versions.toml` (Plan 02-01 Task 1)
- [ ] client-v2 `com.clickhouse:client-v2:0.9.8` + `kotlinx-coroutines-core:1.11.0` (for `future.await()`) on the main/test classpath (Plan 02-01 Task 1)
- [ ] Deterministic `SpanFixtures` builder (full / minimal / truncated-payload / multi-span-single-TraceId) for every downstream IT + unit test (Plan 02-01 Task 2)
- [ ] Test scaffolds created by their owning plans: `ClickHouseSchemaIT` (02-02), `SpanRepositoryIT` (02-03), `AgentSpanRowMapperTest` + `SpanWriterTest` + `PartCountIT` (02-04)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| (none) | — | — | — |

*All phase behaviors have automated verification via Testcontainers against real ClickHouse + a MockK/coroutines-test unit layer.*

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (ClickHouseTestSupport + SpanFixtures + deps; per-IT scaffolds owned by their plans)
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** planned — set `wave_0_complete: true` after Plan 02-01 lands.
