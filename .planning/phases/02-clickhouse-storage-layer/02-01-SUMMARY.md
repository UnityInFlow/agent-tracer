---
phase: 02-clickhouse-storage-layer
plan: 01
subsystem: storage
tags: [clickhouse, testcontainers, fixtures, dependencies, span-identity]
requires: []
provides:
  - "SpanContext envelope-identity carrier (traceId/spanId/parentSpanId/timestamp)"
  - "ClickHouseTestSupport shared singleton container + client-v2 factory"
  - "SpanFixtures deterministic (AgentSpan, SpanContext) builders + traceOf(n) helper"
  - "Version catalog + backend classpath: client-v2, coroutines-core, testcontainers, kotlin-logging, logback"
affects:
  - "Plans 02-02 / 02-03 / 02-04 (ClickHouseSchemaIT, SpanRepositoryIT, PartCountIT, SpanWriterTest depend on this test seam)"
tech-stack:
  added:
    - "com.clickhouse:client-v2:0.9.8"
    - "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0"
    - "org.testcontainers:clickhouse:1.21.4"
    - "org.testcontainers:junit-jupiter:1.21.4"
    - "io.github.oshai:kotlin-logging-jvm:8.0.4"
    - "ch.qos.logback:logback-classic:1.5.34"
  patterns:
    - "Single JVM-wide lazily-started ClickHouseContainer reused across all *IT classes (pay ~90s spin-up once)"
    - "Container-generated credentials read via Testcontainers API — never hardcoded literals (T-02-01)"
    - "SpanContext seam supplies OTLP envelope identity that AgentSpan (attributes-only view) omits"
key-files:
  created:
    - "backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanContext.kt"
    - "backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseTestSupport.kt"
    - "backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanFixtures.kt"
  modified:
    - "gradle/libs.versions.toml"
    - "backend/build.gradle.kts"
decisions:
  - "kotlin-logging 8.0.4 and logback 1.5.34 confirmed as the latest published versions on Maven Central (research A2 [ASSUMED] resolved) — no patch substitution needed"
  - "ClickHouseTestSupport modelled as a plain Kotlin `object` with a `by lazy` container (start once, Ryuk teardown) rather than the JUnit `@Container @JvmStatic` lifecycle — gives true JVM-wide reuse across separate IT classes, not just per-class"
metrics:
  duration: ~4 min
  completed: 2026-06-22
---

# Phase 2 Plan 1: Storage Wave 0 — Dependencies, SpanContext, and Test Fixtures Summary

Established the first ClickHouse dependency, the first `storage/` production type, and the
Testcontainers + deterministic-fixture seam that every downstream Phase-2 storage test
(`ClickHouseSchemaIT`, `SpanRepositoryIT`, `PartCountIT`, `SpanWriterTest`) will run on.

## What Was Built

- **Version catalog + backend build wiring** — added `client-v2 0.9.8`, `coroutines-core 1.11.0`,
  `testcontainers 1.21.4` (clickhouse + junit-jupiter), `kotlin-logging-jvm 8.0.4`, and
  `logback-classic 1.5.34` to `gradle/libs.versions.toml`, all via `version.ref` matching the
  existing catalog style. Wired onto the backend classpath through `libs.*` accessors only —
  no literal version strings in `build.gradle.kts`.
- **`SpanContext`** — immutable `data class` carrying the four OTLP-envelope identity fields
  (`traceId`, `spanId`, `parentSpanId`, `timestamp`) that `AgentSpan` (an attributes-only view)
  deliberately omits. It is the sole source of the schema-mandatory
  `TraceId`/`SpanId`/`ParentSpanId`/`Timestamp` columns; minted by fixtures in Phase 2, populated
  by the OTLP receiver in Phase 3.
- **`ClickHouseTestSupport`** — a Kotlin `object` exposing a single lazily-started
  `ClickHouseContainer` pinned to `clickhouse/clickhouse-server:26.3`, reused JVM-wide, plus a
  `newClient()` factory building a client-v2 `Client` against the container's host / mapped 8123
  port / username / password (read from the container API, never literals). No `Thread.sleep`;
  relies on Testcontainers' built-in wait strategy.
- **`SpanFixtures`** — deterministic `(AgentSpan, SpanContext)` builders: `full()` (every field
  populated), `minimal()` (only `outcome` set, all nullable fields null, still a real fixed
  identity), `truncatedPayload()` (`payloadTruncated = true`, original size > 256 KB), and
  `traceOf(n)` minting N spans on ONE TraceId with distinct SpanIds, monotonic timestamps, and
  **uniform** agentName/model across the trace for `anyLast` rollup determinism.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | Add storage + test dependencies to catalog and backend build | b04d6ca | gradle/libs.versions.toml, backend/build.gradle.kts |
| 2 | Add SpanContext carrier, shared Testcontainers fixture, and SpanFixtures | 98f9604 | storage/SpanContext.kt, storage/ClickHouseTestSupport.kt, storage/SpanFixtures.kt |

## Verification

- `./gradlew :backend:compileKotlin :backend:compileTestKotlin` — BUILD SUCCESSFUL; all six new
  artifacts resolved from Maven Central with no version conflict (transitive coroutines 1.10.2
  cleanly upgraded to 1.11.0).
- `./gradlew :backend:dependencies --configuration testRuntimeClasspath | grep …` — confirms
  `client-v2:0.9.8`, `kotlinx-coroutines-core:1.11.0`, both testcontainers modules `1.21.4`,
  `kotlin-logging-jvm:8.0.4`, and `logback-classic:1.5.34` on the classpath.
- `./gradlew :backend:lintKotlin` — BUILD SUCCESSFUL (ktlint clean; one chain-method-continuation
  autofix applied by `formatKotlin`).
- Image is pinned to `clickhouse/clickhouse-server:26.3` — never `:latest`. No `var`, no `!!`, no
  `Thread.sleep`, no literal credentials.

## Deviations from Plan

None — plan executed exactly as written. The `kotlin-logging` (8.0.4) and `logback` (1.5.34)
versions flagged `[ASSUMED]` in research (A2) were confirmed against Maven Central during Task 1
as the current latest published releases, so no patch substitution was required.

## Threat Surface

- **T-02-01 (Information Disclosure — ClickHouse credentials)** — mitigated as planned:
  `ClickHouseTestSupport.newClient()` reads `container.username` / `container.password` from the
  Testcontainers API; no password literal appears anywhere in the fixture.
- **T-02-SC (supply-chain / Maven installs)** — accepted per the threat register; all six
  artifacts are official org-published and version-verified against `repo1.maven.org`. No
  npm/pip/crates slopcheck checkpoint applies to Maven coordinates.

No new security-relevant surface introduced beyond the plan's `<threat_model>`.

## Self-Check: PASSED

- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanContext.kt
- FOUND: backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseTestSupport.kt
- FOUND: backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanFixtures.kt
- FOUND commit: b04d6ca (Task 1)
- FOUND commit: 98f9604 (Task 2)
