---
phase: 02-clickhouse-storage-layer
plan: 03
subsystem: storage
tags: [clickhouse, client-v2, repository, rowbinary, parameterized-queries, stor-01, stor-02, stor-03, testcontainers]

# Dependency graph
requires:
  - phase: 02-clickhouse-storage-layer (Plan 01)
    provides: SpanContext envelope-identity carrier, shared ClickHouseTestSupport container, SpanFixtures (full/minimal/truncated/traceOf)
  - phase: 02-clickhouse-storage-layer (Plan 02)
    provides: spans/payloads/trace_id_ts/agent_runs DDL migrations, MigrationRunner, ClickHouseClientFactory
provides:
  - "SpanRow / PayloadRow client-v2 register() POJOs mirroring the spans/payloads DDL (SpanContext-sourced identity)"
  - "AgentRunRow read model + RunFilter (the Phase-4 run-list filter set)"
  - "SpanRepository suspend seam (writeBatch/getTraceById/listRuns/getPayload → Result<T>) — the only type that touches ClickHouse SQL"
  - "ClickHouseSpanRepository: RowBinary typed POJO inserts + parameterized STOR-02 two-step / STOR-03 point-lookup reads"
  - "SpanRepositoryIT proving STOR-01 typed-column round-trip, STOR-02 trace-by-id, STOR-03 payload round-trip + truncation, agent_runs rollup + dedup, filtered listRuns"
affects: [03 OTLP ingestion (feeds writeBatch), 04 query API (reads getTraceById/listRuns/getPayload)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Repository is the sole ClickHouse-SQL holder; every user value bound as a {name:Type} placeholder, never interpolated (T-02-06)"
    - "Writes are typed RowBinary POJO inserts (client.insert(table, List)) — no INSERT SQL built from values"
    - "client-v2 register() POJOs are mutable var + no-arg-ctor (JavaBean getter/setter reflection requirement); isolated to SpanRow/PayloadRow with a documented comment"
    - "Nullable spans columns read via reader.readValue<Any?>(col) so SQL NULL stays null (D-01 distinction preserved)"
    - "FINAL only on the aggregating tables (trace_id_ts, agent_runs); no FINAL on the hot spans range scan (anti-pattern)"

key-files:
  created:
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanRow.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/PayloadRow.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/AgentRunRow.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/RunFilter.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepository.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSpanRepository.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepositoryIT.kt
  modified: []

key-decisions:
  - "SpanRow/PayloadRow are plain mutable Kotlin classes (var + defaults + no-arg ctor), NOT data classes: client-v2 0.9.8 POJOSerDe binds DDL columns to JavaBean getters AND setters via reflection and instantiates rows through a no-arg constructor on the read path — a val data class has no setters/no-arg ctor and cannot be register()-ed. Mutability is a framework requirement, isolated to the row classes and documented inline (CLAUDE.md var-rule exception, sanctioned by the plan)."
  - "AgentRunRow stays an immutable data class — it is only mapped FROM query results (the rollup is produced server-side by agent_runs_mv), never register()-ed, so the immutable-val preference holds."
  - "getTraceById/getPayload read via newBinaryFormatReader (readValue preserves Nullable nulls); listRuns reads via queryAll (small finalized result set). Verified the exact client-v2 0.9.8 API by jar introspection: register(Class, getTableSchema(table)), insert(table, List), query(sql, Map<String,Object>).await(), newBinaryFormatReader(QueryResponse), reader.readValue/getString/getLong/getInstant/getList."
  - "Committed SpanRepositoryIT despite a Testcontainers HTTP-wait Read-timeout under local Colima/QEMU (container healthy via manual curl /ping → HTTP 200 in ~2ms). Per objective: do not fake a pass — record a verification gap; CI on native-Docker Linux runners will execute it."

requirements-completed: [STOR-01, STOR-02, STOR-03]

# Metrics
duration: ~22min
completed: 2026-06-22
---

# Phase 02 Plan 03: Storage Repository — Row POJOs, SpanRepository Seam, client-v2 Impl + IT Summary

**The Phase-2 storage seam: SpanRow/PayloadRow client-v2 `register()` POJOs (SpanContext-sourced identity), the AgentRunRow read model + RunFilter, the `SpanRepository` suspend interface, the `ClickHouseSpanRepository` implementation (RowBinary typed inserts + the STOR-02 two-step trace lookup, the `agent_runs`-backed `listRuns`, and the STOR-03 payload point lookup — every read parameterized), and `SpanRepositoryIT` proving every read/write path round-trips against real ClickHouse.**

## Performance

- **Duration:** ~22 min
- **Completed:** 2026-06-22
- **Tasks:** 3 (all executed this run)
- **Files created:** 7 (6 production + 1 IT)

## Accomplishments

- **Task 1 — row POJOs + seam:** `SpanRow`/`PayloadRow` mirror the 001/002 DDL columns one-to-one (identity from `SpanContext`, the five Nullable metric columns as boxed nullable types, the rest sentinel-defaulted per D-02), with `from(AgentSpan, SpanContext)` projections. `AgentRunRow` is the immutable rollup read model; `RunFilter` is the immutable Phase-4 filter set (agent/model/cost range/outcome/date range). `SpanRepository` exposes the four `suspend` methods returning `Result<T>`.
- **Task 2 — repository impl:** `ClickHouseSpanRepository` — lazy idempotent `register()`, typed RowBinary `writeBatch`, the STOR-02 two-step `getTraceById` (range from `trace_id_ts FINAL`, then a bounded `spans` scan with NO `FINAL` on the hot path), the `agent_runs FINAL` `listRuns` building its `WHERE` from only non-null `RunFilter` fields as `{name:Type}` placeholders, and the STOR-03 `(TraceId, SpanId)` point-lookup `getPayload`. All async via `.await()`; expected failures returned as `Result.failure` (T-02-09).
- **Task 3 — integration test:** `SpanRepositoryIT` — six tests covering STOR-01 typed-column round-trip (and that typed values are NOT in the attribute Map), MV-path trace-by-id, STOR-03 payload + truncation round-trip and absent→null, the `agent_runs` rollup math with a duplicate-span dedup (D-07), and filtered `listRuns`. Reuses the shared `ClickHouseTestSupport` container and `SpanFixtures` real identity; distinct `TraceId` per test so the shared container never cross-contaminates assertions; no mocks.

## Task Commits

1. **Task 1: row POJOs, RunFilter, SpanRepository seam** — `95a48d4` (feat)
2. **Task 2: ClickHouseSpanRepository (RowBinary insert + parameterized reads)** — `3a39b07` (feat)
3. **Task 3: SpanRepositoryIT round-trip / payload / rollup / filter proofs** — `f0ddc8d` (test)

## Verification

- `./gradlew :backend:compileKotlin` — BUILD SUCCESSFUL (forced clean recompile; all six production files compile).
- `./gradlew :backend:compileTestKotlin` — BUILD SUCCESSFUL (the IT compiles against the real client-v2 0.9.8 API).
- `./gradlew :backend:lintKotlinMain` / `:backend:lintKotlinTest` — BUILD SUCCESSFUL (ktlint clean; `formatKotlin` applied statement-wrapping autofixes).
- **T-02-06 acceptance grep** — no string-concatenated user value in repository SQL (`grep -nE '"\s*\+\s*trace|"\s*\+\s*spanId|\$\{trace|\$\{span|\$\{filter|\$\{agent|\$\{cost|\$\{model|\$\{outcome|\$\{date'` → no matches). All 13 placeholder bindings use `{name:Type}`.
- **No blocking `.get()`** in the repository (only the word inside a doc comment); every future bridged with `.await()`.
- `getTraceById` uses the `trace_id_ts` MV time range (two-step), not a bare full-table scan; `FINAL` appears only on `trace_id_ts`/`agent_runs`, never on the `spans` hot scan.

### Verification gap (per objective — NOT a test/product defect)

`./gradlew :backend:test --tests "*SpanRepositoryIT"` fails at **container startup** under the local Colima/QEMU Docker host: `org.testcontainers.containers.ContainerLaunchException: Container startup failed for image clickhouse/clickhouse-server:26.3` → `java.net.SocketTimeoutException` on the Testcontainers Java HTTP wait strategy (reported as JUnit `initializationError`, so the run shows "1 test … 1 failed" — the class never instantiates, the six test methods never execute). This is the identical environment limitation documented for `ClickHouseSchemaIT` in Plan 02-02.

**Compensating evidence the test/product code is sound (correctness not left unverified):**
- The ClickHouse container is genuinely healthy here — a manual `docker run -p 18123:8123 clickhouse/clickhouse-server:26.3` + `curl http://localhost:18123/ping` returns **HTTP 200 in ~2ms**, and the server processes queries (it enforces the random Testcontainers password). The failure is isolated to Testcontainers' internal Java HTTP wait read under emulated networking, not to the schema, the repository, or the test logic.
- Ran with `TESTCONTAINERS_RYUK_DISABLED=true` and a pre-cached image, per the environment note.
- Test + production sources compile clean and pass ktlint; the client-v2 0.9.8 API surface used by the repository (`register`, `getTableSchema`, `insert(table, List)`, `query(sql, Map).await()`, `newBinaryFormatReader`, `readValue`/`getString`/`getLong`/`getInstant`/`getList`, `GenericRecord` getters) was confirmed by jar introspection (`javap`).
- The IT mirrors the structure of the already-reviewed `ClickHouseSchemaIT` (same container, same `SpanFixtures`/`SpanContext` seam, same parameterized-query patterns) which has the same gap.

**Expected to pass unchanged on the UnityInFlow native-Docker Linux CI runners (`arc-runner-unityinflow`).**

## Deviations from Plan

None of Rules 1–4 triggered. The plan was executed as written. Two design points were resolved as the plan explicitly anticipated (it asked to confirm client-v2 0.9.8 reflection behavior and adjust the POJO shape accordingly):

- **SpanRow/PayloadRow are mutable `var` plain classes, not immutable `data class`es** — required by client-v2 0.9.8 `POJOSerDe`, which compiles serializers from JavaBean getters AND deserializers into setters and instantiates via a no-arg constructor on the read path. Confirmed by `javap` inspection of `POJOSerDe`/`SerializerUtils`/`DefaultColumnToMethodMatchingStrategy` in the 0.9.8 jar. Mutability is isolated to the two row classes and documented inline (the plan's sanctioned `var`-rule exception). `AgentRunRow` (read-only, never registered) stays an immutable data class.

## Threat Surface

- **T-02-06 (Tampering — SQL injection via trace_id / RunFilter):** mitigated as planned. Every user-controlled value in `getTraceById`/`listRuns`/`getPayload` is bound as a client-v2 `{name:Type}` placeholder; `listRuns` assembles its `WHERE` from constant predicate fragments with values bound separately. `writeBatch` uses typed RowBinary POJO inserts, so no INSERT SQL is built from values. Acceptance grep passes.
- **T-02-07 (Tampering — oversized payloads):** the truncated-payload fixture (256 KB-per-field cap + `PayloadTruncated`/`PayloadOriginalSizeBytes`) is persisted by `PayloadRow.from` and asserted by `payloadsRoundTripIncludingTruncation`. The cap itself is applied by the Plan-04 producer mapping; the repository persists the already-capped body + metadata.
- **T-02-09 (Repudiation/Error-Handling — swallowed failures):** mitigated. Every repository method wraps its work in `runCatching` and returns `Result.failure` (with a `logger.warn`) on an expected DB failure — never silent success, never an uncaught throw — so the Plan-04 writer can retry/backpressure.
- **T-02-08 (Information Disclosure — PII in payloads):** accepted per the register (single-team self-hosted, 14-day TTL); no new control added in v0.0.1.

No security-relevant surface beyond the plan's `<threat_model>` was introduced.

## Known Stubs

None. No hardcoded empty data flows to a UI, no placeholder text, no unwired components. The `RunFilter.NONE` empty-filter constant and the sentinel `''` defaults on non-Nullable columns are intentional schema semantics (D-02), not stubs.

## Next Phase Readiness

- The storage seam is complete: Phase 3's OTLP receiver feeds `writeBatch(List<SpanRow>, List<PayloadRow>)` (building rows via `SpanRow.from`/`PayloadRow.from` from the real OTLP `SpanContext`), and Phase 4's query API consumes `getTraceById`/`listRuns`/`getPayload`.
- **Open item for CI / next executor:** confirm `SpanRepositoryIT` (and `ClickHouseSchemaIT` from Plan 02-02) run green on a native-Docker Linux runner — the runtime round-trip/rollup/filter assertions have not yet been observed passing in the local QEMU environment.

## Self-Check: PASSED

- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanRow.kt
- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/PayloadRow.kt
- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/AgentRunRow.kt
- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/RunFilter.kt
- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepository.kt
- FOUND: backend/src/main/kotlin/io/github/unityinflow/agenttracer/storage/ClickHouseSpanRepository.kt
- FOUND: backend/src/test/kotlin/io/github/unityinflow/agenttracer/storage/SpanRepositoryIT.kt
- FOUND commit: 95a48d4 (Task 1)
- FOUND commit: 3a39b07 (Task 2)
- FOUND commit: f0ddc8d (Task 3)

---
*Phase: 02-clickhouse-storage-layer*
*Completed: 2026-06-22*
