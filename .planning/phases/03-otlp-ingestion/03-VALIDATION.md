---
phase: 3
slug: otlp-ingestion
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-06-22
updated: 2026-06-22
---

# Phase 3 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution. Derived from 03-RESEARCH.md §Validation Architecture and the four PLAN.md verify blocks.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit Jupiter 5.14.4 (runner) + Kotest assertions 6.1.11 (matchers) + Testcontainers ClickHouse 1.21.4 + ktor-server-test-host 3.5.0 + in-process gRPC channel |
| **Config file** | `backend/build.gradle.kts` (`tasks.test { useJUnitPlatform() }`) |
| **Quick run command** | `./gradlew :backend:test --tests "*Otlp*Test"` (pure JVM, no Docker) |
| **Full suite command** | `./gradlew :backend:test` (includes Testcontainers ITs) |
| **Estimated runtime** | unit ~seconds; full suite ~90s once (shared container spin-up paid once) |

> **JUnit skip trap (Pitfall 5):** every `@Test` MUST have `: Unit`/block body — an expression-body `@Test` returning a non-Unit matcher is silently SKIPPED. The phase gate asserts **0 skipped**.
> **Testcontainers caveat (Phase-2 carry):** under Colima/QEMU the ClickHouse HTTP wait read-times-out (container healthy via manual curl). ITs run green on native Docker / CI. Mark ITs "observe green on native Docker" — never fake a pass.

---

## Sampling Rate

- **After every task commit:** `./gradlew :backend:test --tests "*Otlp*Test"` (pure-JVM unit tests, fast feedback)
- **After every plan wave:** `./gradlew :backend:test` (includes Testcontainers ITs)
- **Before `/gsd-verify-work`:** full suite GREEN, 0 skipped (assert the skip count) on native Docker
- **Max feedback latency:** unit < ~10s; wave/full ~90s (container reuse)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 3-01-01 | 03-01 | 1 | INGEST-01/02/03 | T-03-SC | First-party Maven Central deps only; protobuf-java pinned 4.35.1 | build | `./gradlew :backend:dependencies --configuration runtimeClasspath \| grep -E "protobuf-java:4.35.1\|grpc-netty-shaded:1.82.0"` | ✅ created in-plan | ⬜ pending |
| 3-01-02 | 03-01 | 1 | INGEST-01/02/03 | T-03-03 | ReceiverConfig require() rejects bad ports/sizes | unit (via Task 3 test) | `./gradlew :backend:compileKotlin :backend:compileTestKotlin` | ✅ created in-plan | ⬜ pending |
| 3-01-03 | 03-01 | 1 | INGEST-01/02/03 (D-06/D-02/D-07) | T-03-01, T-03-02 | Decoder never throws; tolerant per-span reject; D-07 hex round-trip | unit | `./gradlew :backend:test --tests "*OtlpDecoderTest"` | ❌ W0 (this task) | ⬜ pending |
| 3-02-01 | 03-02 | 2 | INGEST-01, INGEST-03 | T-03-04, T-03-05, T-03-06 | Saturation-first (no decode); body-size guard; 400 on undecodable | compile | `./gradlew :backend:compileKotlin` | ✅ created in-plan | ⬜ pending |
| 3-02-02 | 03-02 | 2 | INGEST-01 (SC3) | T-03-05, T-03-06 | 200 full/partial; 400; 429+Retry-After (no decode); 413 oversized | unit (test-host) | `./gradlew :backend:test --tests "*OtlpHttpRoutesTest"` | ❌ W0 (this task) | ⬜ pending |
| 3-03-01 | 03-03 | 2 | INGEST-02, INGEST-03 | T-03-09, T-03-10 | Coroutine bridge (no runBlocking/Thread.sleep); RESOURCE_EXHAUSTED | compile + grep | `./gradlew :backend:compileKotlin` | ✅ created in-plan | ⬜ pending |
| 3-03-02 | 03-03 | 2 | INGEST-02 (SC3) | T-03-09 | Success(partial unset)/partial(rejected==1)/RESOURCE_EXHAUSTED(no decode) | unit (in-process gRPC) | `./gradlew :backend:test --tests "*OtlpGrpcServiceTest"` | ❌ W0 (this task) | ⬜ pending |
| 3-04-01 | 03-04 | 3 | INGEST-01/02/03 (D-04) | T-03-12, T-03-13, T-03-14 | Migrate-before-serve; ports→drain→close shutdown order | compile | `./gradlew :backend:compileKotlin` | ✅ created in-plan | ⬜ pending |
| 3-04-02 | 03-04 | 3 | INGEST-01 (SC1), INGEST-02 (SC2), D-07, D-02 | T-03-08 | Real HTTP exporter round-trip (getTraceById); real gRPC >4 MB truncated-not-rejected | integration (Testcontainers) | `./gradlew :backend:test --tests "*OtlpHttpReceiverIT" --tests "*OtlpGrpcReceiverIT"` | ❌ W0 (this task) | ⬜ pending |
| 3-04-03 | 03-04 | 3 | INGEST-03 (D-04) | T-03-13, T-03-14 | Schema present after boot; drain flushes in-flight (no loss) | integration (Testcontainers) | `./gradlew :backend:test --tests "*TracerApplicationIT"` | ❌ W0 (this task) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

> Sampling continuity check: no 3 consecutive tasks lack an automated verify — every task above has an `<automated>` command (compile/build counts as automated feedback for non-test wiring tasks, each immediately followed by a test task in the same plan).

---

## Grep Gates (deterministic, non-test)

| Gate | Command | Pass Condition | Source |
|------|---------|----------------|--------|
| No per-request insert (INGEST-03 / SC4) | `grep -rl ClickHouseSpanRepository backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest` | no output | D-06/INGEST-03 |
| No blocking in gRPC service (Pitfall 2) | `grep -v '^\s*\*' backend/src/main/kotlin/io/github/unityinflow/agenttracer/ingest/OtlpGrpcService.kt \| grep -c -E 'runBlocking\|Thread\.sleep'` → `0` | exactly 0 | Pitfall 2 |
| protobuf-java pinned (Pitfall 3) | `./gradlew :backend:dependencies --configuration runtimeClasspath \| grep -c 'protobuf-java:4.35.1'` → `>=1` | at least 1 | Pitfall 3 |
| 0 skipped at phase gate (Pitfall 5) | `./gradlew :backend:test` output | "0 skipped" | Pitfall 5 |

---

## Wave 0 Requirements (test scaffolds — created in their owning plan, TDD where unit-testable)

- [ ] `ingest/OtlpDecoderTest.kt` + `ingest/OtlpRequestFixtures.kt` — D-06/D-02/D-07 decode + hex round-trip (03-01)
- [ ] `ingest/OtlpHttpRoutesTest.kt` — status-code conformance via ktor-server-test-host (03-02)
- [ ] `ingest/OtlpGrpcServiceTest.kt` — in-process gRPC channel + saturation status (03-03)
- [ ] `ingest/OtlpHttpReceiverIT.kt`, `ingest/OtlpGrpcReceiverIT.kt`, `app/TracerApplicationIT.kt` — real-exporter → Testcontainers ClickHouse (03-04)
- [ ] Reuse `storage/ClickHouseTestSupport` (shared container) + `storage/SpanFixtures` (hex constants) + `convention/SpanFixtures` (KeyValue builders) — do NOT re-create fixtures.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `telemetrygen traces` generic-source smoke | INGEST-01/02 (D-03) | telemetrygen is a Go binary, not a JVM unit-test dep | Optional manual/CI check; the real OTel SDK exporter ITs (03-04) are the automatable proof bar. |

*All phase success criteria have automated verification; telemetrygen is an optional extra generic-source check, not a gate.*

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references (all five test files scheduled in their owning plans)
- [x] No watch-mode flags
- [x] Feedback latency acceptable (unit ~seconds; full ~90s container reuse)
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-06-22 (planner-derived; confirm at `/gsd-verify-work`)
