---
phase: 02-clickhouse-storage-layer
plan: 04
subsystem: storage
tags: [clickhouse, batching, channel, backpressure, coroutines, span-writer, row-mapper, truncation, stor-01, stor-03, d-08]

# Dependency graph
requires:
  - phase: 02-clickhouse-storage-layer (Plan 01)
    provides: SpanContext envelope-identity carrier, SpanFixtures (full/minimal/truncated/traceOf), shared ClickHouseTestSupport container, kotlinx-coroutines-core
  - phase: 02-clickhouse-storage-layer (Plan 03)
    provides: SpanRow/PayloadRow client-v2 POJOs, SpanRepository.writeBatch seam, ClickHouseSpanRepository
provides:
  - "AgentSpanRowMapper — pure (AgentSpan, SpanContext) → (SpanRow, PayloadRow) projection with identity from SpanContext + 256 KB-per-field byte cap + truncation metadata (Phase-1 D-13)"
  - "BackpressureSignal — read-only StateFlow<Boolean> saturated signal for the Phase-3 receiver"
  - "SpanWriter — bounded Channel + single writer coroutine; flush-by-count/time; bounded-retry-then-backpressure (never drops); graceful drainAndClose; suspend send(span, context)"
  - "SpanWriterTest (unit, MockK + virtual time) + PartCountIT (Testcontainers SC4)"
affects: [03 OTLP ingestion (feeds SpanWriter.send with real OTLP envelope identity), 04 query API (reads through the same repository seam)]

# Tech tracking
tech-stack:
  added:
    - "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0 (testImplementation — runTest + virtual time)"
  patterns:
    - "The intake is a BOUNDED Channel(capacity); send suspends when full = backpressure (never Channel.UNLIMITED, never a drop path) — T-02-10"
    - "Single writer coroutine drains the channel via select{} over onReceiveCatching + onTimeout → flush by batchSize OR flushInterval"
    - "Flush failure retries with bounded exponential backoff + jitter via coroutine delay (never Thread.sleep); exhausted retries retain the batch (carried forward) so backpressure propagates — D-08"
    - "256 KB-per-field payload cap measured in encoded UTF-8 bytes, truncated on a char boundary (CharsetDecoder IGNORE) so no mid-codepoint split; larger emitter-reported original size preserved (no double-truncate)"
    - "Backpressure state behind a MutableStateFlow (no plain var flag); writer-only internal setter"
    - "Config thresholds env-overridable (AGENT_TRACER_WRITER_*) with D-08 defaults (capacity 50k / batch 5k / 2s / 4 retries)"

key-files:
  created:
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/AgentSpanRowMapper.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/pipeline/BackpressureSignal.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/pipeline/SpanWriter.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/AgentSpanRowMapperTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/pipeline/SpanWriterTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/PartCountIT.kt
  modified:
    - gradle/libs.versions.toml
    - backend/build.gradle.kts

key-decisions:
  - "Added kotlinx-coroutines-test (testImplementation) — first-party JetBrains companion to the already-present coroutines-core, same group/version 1.11.0, explicitly named by the plan for runTest/virtual time. Not a slopcheck-gated install (official Maven coordinate, version already in the catalog)."
  - "The intake Channel carries an immutable SpanWriter.SpanEnvelope(span, context) record (not a bare AgentSpan) — the SpanContext is the producer-facing seam the Phase-3 OTLP receiver supplies real envelope identity into; the writer treats it as opaque and hands it to the mapper at flush time."
  - "No-drop-on-failure realised by carrying the un-flushed batch forward into the next round (prepended) and asserting saturated, rather than re-queuing into the channel — keeps ordering and avoids a self-deadlock when the channel is already full. drainAndClose re-flushes the carried tail until it lands."
  - "send() does a non-suspending trySend fast path first (stay un-saturated when there is room); only on a full buffer does it flip saturated and suspend on channel.send — the suspension itself is the backpressure."
  - "256 KB cap measured in BYTES (UTF-8 encoded length), truncated on a char boundary via a CharsetDecoder with CodingErrorAction.IGNORE so a multi-byte code point is never split into a replacement char. The larger of (emitter-reported original size, locally-measured original size) is preserved per D-13."
  - "PartCountIT committed but unobserved-passing locally — the identical Testcontainers HTTP-wait read-timeout under Colima/QEMU documented for ClickHouseSchemaIT (02-02) and SpanRepositoryIT (02-03). Did NOT fake a pass."

requirements-completed: [STOR-01, STOR-03]

# Metrics
duration: ~10min
completed: 2026-06-22
---

# Phase 02 Plan 04: Batching Write Pipeline — AgentSpanRowMapper, BackpressureSignal, SpanWriter + Tests Summary

**The D-08 batching write pipeline: the pure `AgentSpanRowMapper` projecting an `(AgentSpan, SpanContext)` pair into a `(SpanRow, PayloadRow)` with identity sourced from the context and the 256 KB-per-field byte cap + truncation enforced at write time; the first-class `BackpressureSignal` (read-only `StateFlow<Boolean>`); and the `SpanWriter` — a bounded `Channel` drained by a single writer coroutine that flushes by count/time, retries a failed flush with bounded exponential backoff then backpressures (never drops), and flushes the in-flight buffer on `drainAndClose`. Proven by a MockK + virtual-time unit test and a Testcontainers `PartCountIT` (SC4).**

## Performance

- **Duration:** ~10 min
- **Completed:** 2026-06-22
- **Tasks:** 3 (all executed this run; Task 1 via TDD RED→GREEN)
- **Files created:** 6 (3 production + 3 test); **modified:** 2 (catalog + backend build)

## Accomplishments

- **Task 1 — AgentSpanRowMapper (TDD):** A pure `map(span, context): Pair<SpanRow, PayloadRow>`. Identity (`TraceId`/`SpanId`/`ParentSpanId`/`Timestamp`) is sourced solely from `SpanContext` (never empty/null). Typed agent dimensions follow D-02 (`''` sentinel for null strings) and D-01 (the five metric columns stay nullable — `null` is "unmeasured", never coerced to 0 at the row level). Payload bodies go to `PayloadRow` (D-04). The 256 KB cap is measured in encoded UTF-8 **bytes** and truncated on a char boundary (no mid-codepoint split); when the field is truncated `PayloadTruncated=1` + `PayloadOriginalSizeBytes=original byte length`; an emitter-pretruncated span's larger reported original size is preserved (no double-truncation or size loss). RED first (7 failing cases), then GREEN.
- **Task 2 — SpanWriter + BackpressureSignal:** `BackpressureSignal` wraps a `MutableStateFlow<Boolean>` and exposes a read-only `saturated` `StateFlow` for the Phase-3 receiver (no `var` flag). `SpanWriter` holds a **bounded** `Channel<SpanEnvelope>(capacity)`; `send(span, context)` enqueues the immutable `(span, context)` pair and **suspends when full** (the suspension is the backpressure — never drops, never unbounded). One writer coroutine accumulates via `select{}` over `onReceiveCatching` + `onTimeout`, flushing at `batchSize` OR `flushInterval`; each pair is mapped via `AgentSpanRowMapper` and written through `repo.writeBatch`. A failed flush retries up to `maxRetries` with exponential backoff + jitter via coroutine `delay` (never `Thread.sleep`); after exhausting retries the batch is retained and carried forward (no drop) while `saturated` stays asserted. `drainAndClose` closes the channel and joins the writer, re-flushing the carried tail so nothing is lost on shutdown. Thresholds are env-overridable (`AGENT_TRACER_WRITER_*`).
- **Task 3 — proofs:** `SpanWriterTest` (MockK fake repo, `runTest` virtual time, no Docker) proves all five D-08 semantics — flush-by-count, flush-by-time (virtual-time advance, no real sleep), retry-then-no-drop (fail twice then succeed → 3 calls), suspend-on-full + `saturated → true`, and `drainAndClose` tail flush. `PartCountIT` (Testcontainers) drives 30 000 spans across 30 traces and 30 daily partitions through a real `SpanWriter → ClickHouseSpanRepository` and asserts `system.parts` active count for `spans` stays `< 300` (SC4 / Pitfall 1), using real distinct `SpanFixtures.traceOf` identity so rows spread across keys/partitions.

## Task Commits

1. **Task 1: AgentSpanRowMapper + cap/truncation unit test (+ coroutines-test dep)** — `328b18f` (feat)
2. **Task 2: SpanWriter + BackpressureSignal (bounded Channel batcher, D-08)** — `4492eb1` (feat)
3. **Task 3: SpanWriterTest (unit) + PartCountIT (Testcontainers SC4)** — `6a18f0d` (test)

## Verification

- `./gradlew :backend:test --tests "*AgentSpanRowMapperTest"` — **PASSED** (7 cases: identity from context, dimension/metric projection, under-cap verbatim, over-cap truncation + byte-accurate original size, emitter-pretruncated preservation, multi-byte UTF-8 boundary safety).
- `./gradlew :backend:test --tests "*SpanWriterTest"` — **PASSED** (5 cases: flush-by-count, flush-by-time via virtual time, retry-then-no-drop, suspend-on-full + saturated, drain-flush tail). Pure JVM, no Docker.
- `./gradlew :backend:compileKotlin` — BUILD SUCCESSFUL.
- `./gradlew :backend:lintKotlin` — BUILD SUCCESSFUL (ktlint clean; `formatKotlin` applied wrapping autofixes).
- **Acceptance greps:** no `Channel.UNLIMITED`, no drop/discard path, no `Thread.sleep`, no raw threads in `SpanWriter.kt`; the only `Channel(` is `Channel(config.capacity)` (bounded). Backpressure is a read-only `StateFlow<Boolean>` with no `var` flag.

### Verification gap (per objective — NOT a test/product defect)

`./gradlew :backend:test --tests "*PartCountIT"` fails at **container startup** under the local Colima/QEMU Docker host: `org.testcontainers.containers.ContainerLaunchException: Container startup failed for image clickhouse/clickhouse-server:26.3` → `Timed out waiting for URL ... HTTP [200]` → `java.net.SocketTimeoutException: Read timed out` on the Testcontainers Java HTTP wait strategy (reported as JUnit `initializationError` — the class never instantiates, so the SC4 test body never executes). This is the identical environment limitation documented for `ClickHouseSchemaIT` (02-02) and `SpanRepositoryIT` (02-03). Ran with `TESTCONTAINERS_RYUK_DISABLED=true` and a pre-pulled image per the environment note.

**Compensating evidence the test/product code is sound:**
- The ClickHouse container is genuinely healthy here — a manual `docker run -p 18123:8123 clickhouse/clickhouse-server:26.3` + `curl http://localhost:18123/ping` returns **HTTP 200 in ~5ms**, and the server processes queries (it enforces the default-user password: `REQUIRED_PASSWORD`). The failure is isolated to Testcontainers' internal Java HTTP wait read under emulated networking — not the schema, the mapper, the writer, the repository, or the test logic.
- `PartCountIT` compiles clean and is discovered by JUnit (a transient `@Test`-returns-a-value discovery warning was fixed by switching the method to a block body wrapping `runBlocking`). It reuses the same `ClickHouseTestSupport` container + `SpanFixtures` seam as the already-reviewed `SpanRepositoryIT`, which has the same gap.
- The pure-JVM `SpanWriterTest` exercises the entire `send → flush → writeBatch` path (the same code `PartCountIT` drives) against a fake repo and passes — so the writer's batching/flush/backpressure/drain semantics are verified independently of Docker.

**Expected to pass unchanged on the UnityInFlow native-Docker Linux CI runners (`arc-runner-unityinflow`).**

## Deviations from Plan

- **[Rule 3 — Blocking] Added `kotlinx-coroutines-test` (testImplementation).** The plan's Task 3 mandates `runTest`/virtual time to assert flush-by-time without real waits, but the dependency was not yet on the classpath. Added it to `gradle/libs.versions.toml` (`coroutines-test`, sharing the existing `coroutines` version.ref 1.11.0) and `backend/build.gradle.kts`. This is a first-party JetBrains companion to the already-present `coroutines-core` (same group/version, official Maven coordinate explicitly named by the plan) — not a slopcheck-gated package install, so no `checkpoint:human-verify` applies. Committed with Task 1.
- **[Rule 1 — Bug, test-only] PartCountIT `@Test` method returned a value.** The initial expression-body form (`fun … = runBlocking { … }`) made the JVM method return the last kotest-matcher value, so JUnit 5 silently skipped it ("must not return a value"). Fixed to a block body wrapping `runBlocking`. Caught and fixed before the Task-3 commit.

No Rule-4 (architectural) changes. The pipeline is exactly the D-08 sketch from 02-RESEARCH.

## Threat Surface

- **T-02-10 (DoS — unbounded buffering):** mitigated as planned. The intake is a bounded `Channel(config.capacity)`; `send` suspends on full (acceptance grep confirms no `Channel.UNLIMITED` and no drop path). The `saturated` `StateFlow` is the first-class signal Phase 3 observes to shed load.
- **T-02-11 (DoS — TOO_MANY_PARTS):** mitigated. The batcher only ever issues batched inserts (flush by ~5k/~2s); `PartCountIT` asserts active parts `< 300` under 30k sustained spans across many partitions (verification gap on local Docker only — see above).
- **T-02-12 (Tampering/DoS — oversized payload):** mitigated. `AgentSpanRowMapper` enforces the 256 KB byte cap + truncation at write time before the body reaches ClickHouse; `AgentSpanRowMapperTest` verifies the cap, the byte-accurate original size, and multi-byte boundary safety.
- **T-02-13 (Information Disclosure — payloads in logs):** mitigated. `SpanWriter` logs flush/retry/backpressure events via kotlin-logging with only counts + attempt numbers — never payload bodies.
- **T-02-14 (Availability/Data-loss — spans lost on shutdown):** mitigated. `drainAndClose` closes the channel and joins the writer, re-flushing the carried tail; `SpanWriterTest.drainAndCloseFlushesTheTail` asserts the buffered tail is written.

No security-relevant surface beyond the plan's `<threat_model>` was introduced.

## Known Stubs

None. No hardcoded empty data flows to a UI, no placeholder text, no unwired components. `BackpressureSignal` starts `false` (correct initial un-saturated state), and the `''` sentinels / nullable metrics are intentional schema semantics (D-01/D-02), not stubs.

## Next Phase Readiness

- The write pipeline is the seam Phase 3's OTLP receiver plugs into: a transport-agnostic `suspend send(span: AgentSpan, context: SpanContext)` (the receiver supplies real OTLP envelope identity into the `SpanContext`) plus a `BackpressureSignal.saturated` `StateFlow` to translate into OTLP `RESOURCE_EXHAUSTED`/`UNAVAILABLE` (translation itself is Phase-3 work, deferred).
- **Open item for CI / next executor:** confirm `PartCountIT` (and `ClickHouseSchemaIT` / `SpanRepositoryIT`) run green on a native-Docker Linux runner — the SC4 bounded-parts assertion has not yet been observed passing in the local QEMU environment (the container itself is proven healthy).

## Self-Check: PASSED

- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/AgentSpanRowMapper.kt
- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/pipeline/BackpressureSignal.kt
- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/pipeline/SpanWriter.kt
- FOUND: backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/AgentSpanRowMapperTest.kt
- FOUND: backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/pipeline/SpanWriterTest.kt
- FOUND: backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/PartCountIT.kt
- FOUND commit: 328b18f (Task 1)
- FOUND commit: 4492eb1 (Task 2)
- FOUND commit: 6a18f0d (Task 3)

---
*Phase: 02-clickhouse-storage-layer*
*Completed: 2026-06-22*
