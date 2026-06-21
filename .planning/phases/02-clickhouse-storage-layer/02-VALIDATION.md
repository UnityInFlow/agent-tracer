---
phase: 2
slug: clickhouse-storage-layer
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-06-21
---

# Phase 2 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5.14.4 + Kotest 6.1.11 assertions + Testcontainers (clickhouse 1.21.4) + MockK |
| **Config file** | `backend/build.gradle.kts` (test task) |
| **Quick run command** | `./gradlew :backend:test --tests "io.github.unityinflow.agenttracer.storage.*" -DexcludeTags=integration` |
| **Full suite command** | `./gradlew :backend:test` |
| **Estimated runtime** | ~90 seconds (Testcontainers ClickHouse spin-up dominates) |

---

## Sampling Rate

- **After every task commit:** Run quick run command (unit-only, excludes Testcontainers)
- **After every plan wave:** Run full suite command
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | STOR-01/02/03 | — | TBD | integration/unit | TBD | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky — populated by the planner from RESEARCH.md Validation Architecture.*

---

## Wave 0 Requirements

- [ ] Testcontainers ClickHouse fixture (`@Container` ClickHouseContainer on `clickhouse/clickhouse-server:26.3`) — shared base for integration tests
- [ ] `org.testcontainers:clickhouse:1.21.4` + JUnit 5 + Kotest + MockK added to `backend/build.gradle.kts` and `gradle/libs.versions.toml`
- [ ] client-v2 `com.clickhouse:client-v2:0.9.8` + `kotlinx-coroutines-core` (for `future.await()`) on the test/main classpath

*Detailed per-task entries filled by the planner against RESEARCH.md's "## Validation Architecture" section.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| (none expected) | — | — | — |

*Target: all phase behaviors have automated verification via Testcontainers against real ClickHouse.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
