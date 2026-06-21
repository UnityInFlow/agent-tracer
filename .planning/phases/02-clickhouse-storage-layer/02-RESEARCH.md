# Phase 2: ClickHouse Storage Layer - Research

**Researched:** 2026-06-21
**Domain:** ClickHouse time-series trace storage (MergeTree schema, AggregatingMergeTree rollup MVs, client-v2 batch inserts, coroutine write pipeline, startup migrations, Testcontainers)
**Confidence:** HIGH (schema patterns + client-v2 API + Testcontainers verified against ClickHouse official docs, clickhouse-java repo, and Maven Central; rollup engine choice MEDIUM→HIGH after cross-check)

## Summary

Phase 2 persists `AgentSpan`s into a ClickHouse schema that serves three read shapes — trace-by-id lookup, run-list filtering, and payload-by-span replay — at time-series scale, and proves it against a real ClickHouse via Testcontainers. The locked decisions in `02-CONTEXT.md` (D-01..D-08) already fix the unrecoverable choices; this research pins the *implementation specifics* the planner will template: the exact DDL (with the D-06 engine choice resolved), the `com.clickhouse:client-v2:0.9.8` insert/query API surface, a hand-rolled versioned startup migration runner, the coroutine `Channel` + writer batching pipeline, and the Testcontainers verification approach.

The schema is the consensus four-object ClickHouse-OTel design (one wide `spans` MergeTree table + a `trace_id_ts` lookup MV + an `agent_runs` rollup MV + a separate `payloads` table), already verified against ClickHouse's official observability guide in `ARCHITECTURE.md`. The single open engine question — D-06's `AggregatingMergeTree` vs `SummingMergeTree` for `agent_runs` — resolves to **`AggregatingMergeTree` with `SimpleAggregateFunction` columns**: it cleanly expresses `sum(cost)`, `sum(tokens)`, **and** `max(saturation)` / `min(start)` / `max(end)` in one engine, where `SummingMergeTree` only collapses additively and can't do max-wins (needed for late/out-of-order merge-friendliness per Pitfall 5). `SimpleAggregateFunction` (not full `AggregateFunction`) keeps columns directly readable without `-Merge`/`finalizeAggregation`, so query-time stays simple.

The client-v2 API is `Client.Builder` → `register(POJO, schema)` → `insert(table, List<?>, InsertSettings)` returning `CompletableFuture<InsertResponse>`, bridged to coroutines with `kotlinx.coroutines.future.await()`. Reads use `query(sql)` → `CompletableFuture<QueryResponse>` and a `ClickHouseBinaryFormatReader` / `GenericRecord` iteration. The write pipeline is a bounded `Channel<AgentSpan>` drained by one writer coroutine that flushes at ~5k rows or ~2s, with bounded exponential-backoff retry and producer-suspends backpressure (D-08) exposed as a first-class suspend `send`.

**Primary recommendation:** Template four idempotent `CREATE ... IF NOT EXISTS` DDL resources (spans, payloads, trace_id_ts MV, agent_runs MV-to-AggregatingMergeTree), apply them through a `schema_migrations`-tracked startup runner, write through a bounded-Channel batcher using client-v2 `insert(table, List<AgentSpanRow>)`, and verify every success criterion with Testcontainers `org.testcontainers.clickhouse.ClickHouseContainer` pinned to `clickhouse/clickhouse-server:26.3` — asserting granule pruning via `EXPLAIN indexes = 1` and bounded parts via `system.parts WHERE active = 1`.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Null semantics (D-01 / D-02 / D-03):**
- **D-01:** Use ClickHouse `Nullable(...)` **only on aggregate-feeding metric columns** — cost (`Nullable(Float64)`), input/output tokens (`Nullable(UInt64)`), saturation (`Nullable(Float32)`), plan step (`Nullable(Int64)`). "Unmeasured" must stay distinguishable from a real `0` (Phase-1 D-12; aggregates skip nulls). These columns are kept **out of the ORDER BY / primary key** so Nullable overhead never touches the sort key.
- **D-02:** Every other column uses **sentinel defaults, not Nullable** — empty string / `LowCardinality(String)` for dimensional string fields (agent, model, skill, provider, ids, outcome, operation, status), `''` for absent strings, default `[]` for `Array(String)` (finish reasons). Follows ClickHouse "avoid Nullable" guidance where empty-string is a good "absent."
- **D-03:** Boolean-ish flags (e.g. `payload.truncated`) map to `UInt8` with `0` default; `payloadOriginalSizeBytes` is `Nullable(UInt64)` only if absence must be distinguished, else `0` default — planner's discretion, consistent with the D-01/D-02 split.

**Payload storage placement (D-04 / D-05):**
- **D-04:** Store input/output/system payloads in a **separate `payloads` table** keyed by `(TraceId, SpanId)`, NOT inline in `spans`. Keeps `spans` lean for the hot analytical path; payloads are point-lookup only (STOR-03 "retrieved individually"). Honor the 256 KB-per-field cap (Phase-1 D-13) and the `unityinflow.payload.truncated` / original-size contract at write time.
- **D-05:** The `payloads` table uses the **same TTL window** as `spans` so blobs expire in lockstep — no orphaned payloads, no separate retention policy.

**agent_runs rollup (D-06 / D-07):**
- **D-06:** Build `agent_runs` as an **incremental `AggregatingMergeTree` (or `SummingMergeTree`) materialized view in this phase** — keyed by root span / `TraceId`, with agent, model, start, duration, summed tokens, summed cost, max saturation, outcome, span count. Engine choice + ORDER BY are unrecoverable; decided and pinned now. (This research resolves the open choice → **AggregatingMergeTree + SimpleAggregateFunction**; see §Architecture.)
- **D-07:** **Scope Phase-2 tests** to rollup correctness for complete / in-order traces plus span dedup. Deep late / out-of-order / incomplete-trace replay-merge edge cases (PITFALLS 5/6) are **deferred to Phase 4**. Don't over-invest before a consumer exists — but don't let the schema preclude it (use merge-friendly engines now).

**Write-failure & backpressure policy (D-08):**
- **D-08:** The write pipeline is **bounded retry, then backpressure** — never drop, never queue unboundedly. A bounded coroutine `Channel` accumulates spans; the writer flushes by count (~5k) or time (~2s); a failed flush retries a few times with exponential backoff. When the bounded buffer is full, the producer **suspends** (backpressure). The storage layer **exposes this backpressure signal as a first-class part of its API** so the Phase-3 receiver can map it to OTLP `RESOURCE_EXHAUSTED`/`UNAVAILABLE`. Dropping is rejected. Disk-spill/WAL durability is rejected as over-built for v0.0.1.

### Claude's Discretion
- Exact column naming/casing in DDL (follow ClickHouse otel-exporter / ARCHITECTURE.md schema conventions), `CODEC` choices (`ZSTD`, `Delta`), bloom-filter granularity, partition granularity (`toDate(Timestamp)` per the sketch).
- Migration runner mechanism (hand-rolled versioned DDL at startup vs a tiny library) — startup-applied + versioned is the constraint; the how is open.
- Exact flush thresholds and retry/backoff counts; whether they are env-configurable (a sensible default matching research ranges is fine).
- Repository/DAO API shape, Kotlin package layout under `storage/`, coroutine bridging of client-v2 `CompletableFuture`s via `.await()`.
- Default TTL duration (research shows 14–30 days; pick a sane default, make it overridable) — the *existence* of a TTL clause in v0.0.1 DDL is mandatory; the exact number is discretionary.

### Deferred Ideas (OUT OF SCOPE)
- **Receiver-side backpressure → OTLP status mapping** — storage exposes the signal in Phase 2; translation to `RESOURCE_EXHAUSTED`/`UNAVAILABLE` + OTLP `partial_success` is Phase 3.
- **Deep late/out-of-order/incomplete-trace replay-merge correctness** — schema stays merge-friendly now; max-wins / never-arriving-span semantics are Phase 4.
- **Disk-spill / WAL write durability** — rejected for v0.0.1; revisit only for survive-ClickHouse-restart durability.
- **`async_insert` server-side buffering** — client-side batching is primary; server-side `async_insert=1` is a possible additional fallback, not a v0.0.1 requirement.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **STOR-01** | ClickHouse schema stores spans, traces, and agent_runs with typed first-class columns for agent fields (tokens, cost, saturation, skill, model) | §Architecture — `spans` wide table with promoted typed columns (D-01 Nullable metrics, D-02 LowCardinality dimensions) + `agent_runs` AggregatingMergeTree rollup MV. The `trace_id_ts` MV is the "traces" object. Pitfall 3 (no agent fields buried in Map) is structurally prevented. |
| **STOR-02** | Trace lookup by trace_id is served by a materialized view (no full table scan) | §Architecture Pattern 2 — `trace_id_ts` MV maps `TraceId → (min(Timestamp), max(Timestamp))`; two-step lookup resolves time range then range-scans `spans` so the primary key prunes granules. Bloom-filter skip index on `TraceId` as secondary aid. Verified via `EXPLAIN indexes = 1` (§Validation). |
| **STOR-03** | Span input/output payloads are stored, enabling replay and the span detail panel | §Architecture — separate `payloads` table keyed `(TraceId, SpanId)` (D-04), point-lookup `SELECT ... WHERE TraceId=? AND SpanId=?`. Truncation contract (Phase-1 D-13) honored at write time. Same TTL as `spans` (D-05). |
</phase_requirements>

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Span persistence (typed columns) | Database (ClickHouse `spans` MergeTree) | Backend `storage/` (DDL + row mapping) | Columnar store owns durability + typed agent columns; the repository owns the DDL and the AgentSpan→row projection |
| Trace-by-id lookup | Database (`trace_id_ts` MV + primary key pruning) | Backend repository query method | MV + sort-key pruning is a storage-engine concern; repository exposes a suspend query seam |
| Run-list rollup | Database (`agent_runs` AggregatingMergeTree MV) | Backend repository | Incremental MV self-populates on insert (DDL, not runtime); repository reads it (Phase-4 consumer) |
| Payload store/retrieve | Database (`payloads` table) | Backend repository | Point-lookup blob store, deliberately off the hot analytical path |
| Batching / backpressure | Backend `storage/pipeline` (coroutine Channel + writer) | — | In-process flow control; ClickHouse only sees batched inserts. Backpressure is a JVM-side coroutine concern exposed to Phase-3 |
| Schema migrations | Backend `storage/migrations` (startup runner) | Database (`schema_migrations` table) | Versioned DDL applied at startup; ClickHouse tracks applied versions |
| Insert/query transport | clickhouse client-v2 (HTTP :8123) | Backend repository | client-v2 owns serialization (RowBinary) + HTTP; repository bridges `CompletableFuture` → coroutine |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.clickhouse:client-v2` | 0.9.8 | ClickHouse inserts + queries over HTTP; RowBinary streaming, POJO register, `CompletableFuture` async | ClickHouse's official recommendation for new apps over JDBC `[VERIFIED: Maven Central + clickhouse.com/docs/integrations/java]`. Latest release. RowBinary batch inserts are critical for span ingest throughput (Pitfall 1). |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | All async work (ecosystem rule); provides `kotlinx.coroutines.future.await()` for `CompletableFuture`/`CompletionStage` | Latest stable `[VERIFIED: Maven Central]`. The JDK8 `CompletableFuture.await()` integration was folded into `-core` (the `-jdk8` artifact is now an empty back-compat stub since 1.7+) `[CITED: kotlinlang.org/api/kotlinx.coroutines/.../kotlinx.coroutines.future]`. |
| ClickHouse server | 26.3 LTS (`clickhouse/clickhouse-server:26.3`) | Trace storage | Current LTS line `[CITED: STACK.md, GitHub releases]`. Pin the LTS tag in Docker Compose and Testcontainers — never `latest` (Pitfall 10; non-reproducible). |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.testcontainers:clickhouse` | 1.21.4 | Integration tests against a real ClickHouse | Latest release `[VERIFIED: Maven Central]`. Schema-apply, insert/query round-trip, part-count, EXPLAIN-pruning tests. ClickHouse SQL is too dialect-specific to mock. |
| `org.testcontainers:junit-jupiter` | 1.21.4 | JUnit 5 `@Testcontainers` / `@Container` lifecycle | Pairs the ClickHouse container with the JUnit 5 runner (ecosystem uses JUnit 5). |
| `io.kotest:kotest-assertions-core` | 6.1.11 | Matchers (already in catalog) | Assertions on query results / row counts / part counts. |
| `io.mockk:mockk` | 1.14.11 | Mocking (already in catalog) | Unit tests for the batching pipeline (fake repository to assert flush thresholds/backpressure without a DB). |
| `org.junit.jupiter:junit-jupiter` | 5.14.4 | Test runner (already in catalog) | Ecosystem constraint — JUnit 5, not 6.x. |
| `io.github.oshai:kotlin-logging-jvm` + `ch.qos.logback:logback-classic` | 8.0.4 / 1.5.34 | Logging (writer-coroutine flush/retry/backpressure visibility) | Per STACK.md; add now since the writer coroutine needs operational logging. `[ASSUMED versions — confirm against Maven Central at install]` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `client-v2` | `clickhouse-jdbc` 0.9.8 (JDBC v2 driver) | JDBC ecosystem tooling (pools, ORMs, Flyway). Not needed — RowBinary batch + async future bridge is cleaner; client-v2 is the official new-app recommendation `[CITED: STACK.md]`. |
| Hand-rolled migration runner | A library (e.g. a ClickHouse-aware migrator) | A library adds a dependency and most assume transactional DDL (ClickHouse has none). For 4 idempotent DDL files, ~60 lines of Kotlin + a `schema_migrations` table is simpler and fully under test. CONTEXT D-discretion allows either; hand-rolled recommended. |
| `AggregatingMergeTree` + `SimpleAggregateFunction` | `SummingMergeTree` | Summing only collapses additively — it cannot express `max(saturation)` / `min(start)` / `max(end)`, which D-06 requires and which are exactly the merge-friendly max-wins semantics Pitfall 5 wants preserved. See §Architecture Decision 1. |
| `SimpleAggregateFunction` | full `AggregateFunction` + `-State`/`-Merge` | Full AggregateFunction is needed only for non-trivial states (uniq, quantile). For sum/min/max the simple form stores the final value, so columns read directly with no `finalizeAggregation` — simpler queries, same correctness. |

**Installation (additions to `gradle/libs.versions.toml` + `backend/build.gradle.kts`):**
```toml
# [versions]
clickhouse-client = "0.9.8"
coroutines = "1.11.0"
testcontainers = "1.21.4"
kotlin-logging = "8.0.4"
logback = "1.5.34"

# [libraries]
clickhouse-client-v2  = { module = "com.clickhouse:client-v2", version.ref = "clickhouse-client" }
coroutines-core       = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
testcontainers-clickhouse = { module = "org.testcontainers:clickhouse", version.ref = "testcontainers" }
testcontainers-junit  = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
kotlin-logging        = { module = "io.github.oshai:kotlin-logging-jvm", version.ref = "kotlin-logging" }
logback-classic       = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
```
```kotlin
// backend/build.gradle.kts dependencies
implementation(libs.clickhouse.client.v2)
implementation(libs.coroutines.core)
implementation(libs.kotlin.logging)
runtimeOnly(libs.logback.classic)
testImplementation(libs.testcontainers.clickhouse)
testImplementation(libs.testcontainers.junit)
```

## Package Legitimacy Audit

> The `package-legitimacy` seam supports npm/pypi/crates only — not Maven. All artifacts verified directly against `repo1.maven.org` (Maven Central, authoritative registry, fetched 2026-06-21) and are high-trust org-published artifacts.

| Package | Registry | Age | Source Repo | Verdict | Disposition |
|---------|----------|-----|-------------|---------|-------------|
| `com.clickhouse:client-v2` | Maven Central | Active (0.9.8 latest) | github.com/ClickHouse/clickhouse-java | OK | Approved — official ClickHouse Inc. artifact |
| `org.testcontainers:clickhouse` | Maven Central | Active (1.21.4 latest) | github.com/testcontainers/testcontainers-java | OK | Approved — official Testcontainers module |
| `org.testcontainers:junit-jupiter` | Maven Central | Active (1.21.4 latest) | github.com/testcontainers/testcontainers-java | OK | Approved |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | Maven Central | Active (1.11.0 latest) | github.com/Kotlin/kotlinx.coroutines | OK | Approved — JetBrains artifact |
| `io.github.oshai:kotlin-logging-jvm` | Maven Central | Established | github.com/oshai/kotlin-logging | OK | Approved (version `[ASSUMED]` — confirm 8.0.4 at install) |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### System Architecture Diagram (write path + read seams this phase owns)

```
  Phase-3 receiver (NOT this phase)
        │ producer.send(agentSpan)   ← suspend; SUSPENDS when buffer full (D-08 backpressure)
        ▼
  ┌────────────────────────────────────────────────────────────┐
  │ storage/pipeline  — SpanWriter                              │
  │   Channel<AgentSpan>(capacity = N)   (bounded buffer)       │
  │        │ drained by ONE writer coroutine                    │
  │        ▼                                                     │
  │   accumulate → flush at ≥5k rows OR ≥2s (whichever first)   │
  │        │  bounded retry w/ exponential backoff on failure   │
  └────────┼───────────────────────────────────────────────────┘
           │ client-v2 insert(table, List<SpanRow>)  → CompletableFuture
           │ .await()  (kotlinx.coroutines.future)
           ▼
  ┌────────────────────────────────────────────────────────────┐
  │ ClickHouse (HTTP :8123)                                     │
  │                                                            │
  │   spans  (MergeTree, wide, typed agent columns) ───────────┐│
  │     │ on insert (automatic)                                ││
  │     ├──▶ trace_id_ts   MV  (TraceId → min/max Timestamp)   ││  ← STOR-02 lookup path
  │     └──▶ agent_runs    MV → AggregatingMergeTree rollup    ││  ← STOR-01 "agent_runs"
  │                                                            ││
  │   payloads (MergeTree, keyed (TraceId,SpanId)) ────────────┘│  ← STOR-03 point lookup
  └────────────────────────────────────────────────────────────┘
           ▲
           │ repository read seams (consumed in Phase 4)
   getTraceById(traceId)  ·  listRuns(filters)  ·  getPayload(traceId, spanId)
```

The migration runner runs once at startup *before* the writer coroutine launches, applying the four DDL objects through a `schema_migrations` tracking table.

### Recommended Project Structure
```
backend/src/main/kotlin/io/github/unityinflow/agenttracer/
└── storage/
    ├── ClickHouseConfig.kt        # endpoint/user/password/db from env vars (no secrets in code)
    ├── ClickHouseClientFactory.kt # builds Client.Builder; client-v2 lifecycle
    ├── migrations/
    │   ├── MigrationRunner.kt     # schema_migrations table + ordered .sql apply (idempotent)
    │   └── (reads src/main/resources/migrations/*.sql)
    ├── SpanRow.kt                 # POJO mirroring the spans DDL columns (register() target)
    ├── PayloadRow.kt              # POJO mirroring the payloads DDL columns
    ├── SpanRepository.kt          # interface: suspend writeBatch / getTraceById / listRuns / getPayload
    ├── ClickHouseSpanRepository.kt# client-v2 impl; CompletableFuture.await() bridge
    └── pipeline/
        ├── SpanWriter.kt          # bounded Channel + writer coroutine + flush policy + retry
        └── BackpressureSignal.kt  # first-class API the Phase-3 receiver observes (D-08)

backend/src/main/resources/migrations/
    ├── 001_spans.sql
    ├── 002_payloads.sql
    ├── 003_trace_id_ts_mv.sql
    └── 004_agent_runs_mv.sql

backend/src/test/kotlin/.../storage/
    ├── ClickHouseSchemaIT.kt      # @Testcontainers — DDL applies, EXPLAIN pruning
    ├── SpanRepositoryIT.kt        # insert/query round-trip, payload round-trip, agent_runs rollup
    ├── PartCountIT.kt             # sustained insert → system.parts stays bounded
    └── SpanWriterTest.kt          # unit — flush thresholds + backpressure (MockK fake repo)
```

### Pattern 1: One wide `spans` table + materialized views (verified consensus design)

**What:** A single MergeTree `spans` table holds everything; derived access paths are ClickHouse MVs, not joins. The two opposite ORDER BY mistakes (Pitfall 2) are both avoided by ordering low→high cardinality with `TraceId` last, plus a `trace_id_ts` MV for point lookup.
**When to use:** Always for trace storage in ClickHouse `[CITED: ARCHITECTURE.md Pattern 2; clickhouse.com/blog/storing-traces-and-spans-open-telemetry-in-clickhouse]`.

**Full DDL the planner templates** (column casing follows the otel-exporter convention from ARCHITECTURE.md; CODEC/partition/TTL per D-discretion):

```sql
-- 001_spans.sql
CREATE TABLE IF NOT EXISTS spans (
  Timestamp            DateTime64(9)               CODEC(Delta(8), ZSTD(1)),
  TraceId              String                      CODEC(ZSTD(1)),
  SpanId               String                      CODEC(ZSTD(1)),
  ParentSpanId         String                      CODEC(ZSTD(1)),
  SpanName             LowCardinality(String),
  ServiceName          LowCardinality(String),     -- agent-identity fallback (Phase-1 D-10)
  Duration             UInt64                      CODEC(ZSTD(1)),  -- nanoseconds
  StatusCode           LowCardinality(String),
  -- ── promoted typed agent dimensions (D-02: sentinel '' / LowCardinality, NOT Nullable) ──
  AgentName            LowCardinality(String),
  AgentId              String                      CODEC(ZSTD(1)),
  Provider             LowCardinality(String),
  Model                LowCardinality(String),
  SkillName            LowCardinality(String),
  SkillVersion         LowCardinality(String),
  ToolCallId           String                      CODEC(ZSTD(1)),
  ConversationId       String                      CODEC(ZSTD(1)),
  Operation            LowCardinality(String),     -- gen_ai.operation.name
  Outcome              LowCardinality(String),     -- success|failure|partial|cancelled
  FinishReasons        Array(String)               DEFAULT [],
  SchemaVersion        LowCardinality(String),
  -- ── aggregate-feeding metric columns (D-01: Nullable; kept OUT of ORDER BY) ──
  InputTokens          Nullable(UInt64),
  OutputTokens         Nullable(UInt64),
  CostUsd              Nullable(Float64),
  ContextSaturationPct Nullable(Float32),
  PlanStep             Nullable(Int64),
  -- ── payload truncation metadata (D-03; payload BODIES live in payloads table, D-04) ──
  PayloadTruncated     UInt8                       DEFAULT 0,
  PayloadOriginalSizeBytes Nullable(UInt64),       -- Nullable to distinguish "unknown" from 0 (D-03)
  -- ── everything else stays dynamic ──
  SpanAttributes       Map(LowCardinality(String), String),
  ResourceAttributes   Map(LowCardinality(String), String),
  INDEX idx_trace_id   TraceId TYPE bloom_filter(0.001) GRANULARITY 1
) ENGINE = MergeTree
PARTITION BY toDate(Timestamp)
ORDER BY (ServiceName, SpanName, toUnixTimestamp(Timestamp), TraceId)
TTL toDateTime(Timestamp) + INTERVAL 14 DAY
SETTINGS ttl_only_drop_parts = 1;
```
> **Nullable-vs-sort-key (D-01/D-02):** every `Nullable` column (the 5 metrics + `PayloadOriginalSizeBytes`) is absent from `ORDER BY`. The sort key is all non-Nullable LowCardinality/`String`/timestamp columns, so Nullable's per-value null-map never touches the primary index. `[CITED: clickhouse.com/docs/use-cases/observability/schema-design — "avoid Nullable on sort/index columns"]`

```sql
-- 002_payloads.sql  (D-04: separate table; D-05: same TTL as spans)
CREATE TABLE IF NOT EXISTS payloads (
  TraceId              String      CODEC(ZSTD(1)),
  SpanId               String      CODEC(ZSTD(1)),
  Timestamp            DateTime64(9) CODEC(Delta(8), ZSTD(1)),  -- carried for TTL alignment
  InputPayload         String      CODEC(ZSTD(3)),   -- ≤256 KB (Phase-1 D-13), high ZSTD level for text
  OutputPayload        String      CODEC(ZSTD(3)),
  SystemInstructions   String      CODEC(ZSTD(3)),
  PayloadTruncated     UInt8       DEFAULT 0,
  PayloadOriginalSizeBytes Nullable(UInt64)
) ENGINE = MergeTree
PARTITION BY toDate(Timestamp)
ORDER BY (TraceId, SpanId)                          -- point-lookup key (STOR-03 "retrieved individually")
TTL toDateTime(Timestamp) + INTERVAL 14 DAY
SETTINGS ttl_only_drop_parts = 1;
```

### Pattern 2: `trace_id_ts` lookup MV (STOR-02 — no full scan)

**What:** Map `TraceId → (min(Timestamp), max(Timestamp))`. Trace fetch becomes two steps: read the time range from the MV, then `SELECT ... FROM spans WHERE TraceId=? AND Timestamp BETWEEN ? AND ?` so the primary key + partition prune granules.
**TARGET-table form** (explicit target table is clearer than implicit `.inner.` and lets us pick the engine + TTL):

```sql
-- 003_trace_id_ts_mv.sql
CREATE TABLE IF NOT EXISTS trace_id_ts (
  TraceId  String,
  MinTime  SimpleAggregateFunction(min, DateTime64(9)),
  MaxTime  SimpleAggregateFunction(max, DateTime64(9))
) ENGINE = AggregatingMergeTree
PARTITION BY tuple()
ORDER BY (TraceId)
TTL toDateTime(MaxTime) + INTERVAL 14 DAY;          -- expires with spans (no orphan index rows)

CREATE MATERIALIZED VIEW IF NOT EXISTS trace_id_ts_mv TO trace_id_ts AS
SELECT TraceId, min(Timestamp) AS MinTime, max(Timestamp) AS MaxTime
FROM spans
GROUP BY TraceId;
```
> `SimpleAggregateFunction(min/max, ...)` collapses to one row per `TraceId` across merges with no `-Merge` needed at read time. Lookup query: `SELECT MinTime, MaxTime FROM trace_id_ts FINAL WHERE TraceId = ?` (or `GROUP BY TraceId` to avoid `FINAL`). This is the documented two-step trace fetch `[CITED: ARCHITECTURE.md; otel-collector clickhouseexporter trace_id_ts MV]`.

### Pattern 3: `agent_runs` rollup MV → AggregatingMergeTree (STOR-01 "agent_runs"; resolves D-06)

**What:** Per-trace (= per-run, Phase-1 D-08) rollup so the Phase-4 run-list/filter screen reads direct columns with no `spans` scan. **Engine choice resolved: AggregatingMergeTree + SimpleAggregateFunction** (see §Decision 1).

```sql
-- 004_agent_runs_mv.sql
CREATE TABLE IF NOT EXISTS agent_runs (
  TraceId       String,
  AgentName     SimpleAggregateFunction(anyLast, LowCardinality(String)),
  Model         SimpleAggregateFunction(anyLast, LowCardinality(String)),
  Outcome       SimpleAggregateFunction(anyLast, LowCardinality(String)),
  StartTime     SimpleAggregateFunction(min, DateTime64(9)),
  EndTime       SimpleAggregateFunction(max, DateTime64(9)),
  InputTokens   SimpleAggregateFunction(sum, UInt64),    -- nulls → 0 in the SELECT (see note)
  OutputTokens  SimpleAggregateFunction(sum, UInt64),
  CostUsd       SimpleAggregateFunction(sum, Float64),
  MaxSaturation SimpleAggregateFunction(max, Float32),
  SpanCount     SimpleAggregateFunction(sum, UInt64)
) ENGINE = AggregatingMergeTree
PARTITION BY toDate(StartTime)
ORDER BY (StartTime, TraceId)                        -- run-list is time-ordered; TraceId disambiguates
TTL toDateTime(EndTime) + INTERVAL 14 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS agent_runs_mv TO agent_runs AS
SELECT
  TraceId,
  anyLast(AgentName)            AS AgentName,
  anyLast(Model)               AS Model,
  anyLast(Outcome)             AS Outcome,        -- Phase-2 scope: complete/in-order traces (D-07)
  min(Timestamp)               AS StartTime,
  max(Timestamp)               AS EndTime,
  sum(ifNull(InputTokens, 0))  AS InputTokens,    -- aggregates skip nulls → coerce to 0 for SUM input
  sum(ifNull(OutputTokens, 0)) AS OutputTokens,
  sum(ifNull(CostUsd, 0))      AS CostUsd,
  max(ifNull(ContextSaturationPct, toFloat32(0))) AS MaxSaturation,
  count()                      AS SpanCount
FROM spans
GROUP BY TraceId;
```
> **Read pattern:** `SELECT TraceId, AgentName, Model, Outcome, StartTime, EndTime, InputTokens+OutputTokens AS TotalTokens, CostUsd, MaxSaturation, SpanCount FROM agent_runs FINAL WHERE StartTime BETWEEN ? AND ? AND AgentName=? ...` — every Phase-4 filter (agent, model, cost range, outcome, date range) is a direct column. `FINAL` (or `GROUP BY TraceId` + `-Merge`-free SimpleAggregate read) finalizes partial states from concurrent merges.
> **D-07 scope note:** `anyLast(Outcome)` and the SUMs are correct for complete/in-order traces. Late/out-of-order/never-arriving spans (the max-wins replay-merge correctness problem) are explicitly **deferred to Phase 4** — but because this is AggregatingMergeTree, the schema does NOT preclude switching `Outcome`/timing to max-wins later (Pitfall 5/6 stay reachable without a table rewrite).

### Anti-Patterns to Avoid
- **Per-row / per-request INSERT (Pitfall 1):** never insert one span at a time — the Channel batcher is mandatory architecture, not optimization. Every insert is a part; small inserts → `TOO_MANY_PARTS`.
- **`ALTER UPDATE`/`DELETE` mutations (Pitfall 6):** no mutations anywhere in `storage/`. Status/dedup via insert-new-version + merge semantics; retention via `TTL`, never `DELETE`.
- **Agent fields in the Map column (Pitfall 3):** tokens/cost/saturation/skill/model/agent MUST be typed first-class columns. `Map` is only for non-agent leftovers.
- **`ORDER BY (TraceId, ...)` on `spans` (Pitfall 2):** TraceId is random/high-cardinality — sorting by it destroys compression and turns every analytics query into a full scan. TraceId goes *last* in ORDER BY; point lookup is served by the MV + bloom filter.
- **`SELECT FINAL` everywhere:** use `FINAL` only on the aggregating tables where finalization matters; on the hot `spans` path prefer `GROUP BY span_id` / `LIMIT 1 BY` query-time dedup.
- **`clickhouse/clickhouse-server:latest` (Pitfall 10):** pin `:26.3` in both Compose and Testcontainers.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| ClickHouse row serialization | Manual RowBinary byte encoding / hand-built INSERT SQL with string concat | client-v2 `register(SpanRow::class.java, schema)` + `insert(table, list)` | client-v2 generates RowBinary serializers from the POJO; hand-rolling RowBinary is error-prone and SQL string-concat invites injection + breaks on payloads `[VERIFIED: clickhouse.com/docs/integrations/java]` |
| CompletableFuture→coroutine bridge | Polling `.get()` on a thread / custom callback glue | `kotlinx.coroutines.future.await()` | Cancellable, non-blocking suspend bridge already in coroutines-core. Blocking `.get()` violates the no-blocking-threads ecosystem rule. |
| Spinning up ClickHouse for tests | Shared dev ClickHouse / mocks of SQL | `org.testcontainers.clickhouse.ClickHouseContainer` | Real engine = real merge/TTL/EXPLAIN behavior; ClickHouse SQL can't be meaningfully mocked. |
| Bounded buffer + backpressure | `synchronized` queue + manual wait/notify | `Channel<AgentSpan>(capacity = N)` | Coroutine `Channel` gives suspend-on-full backpressure for free (D-08); `send` suspends when full, `receive` suspends when empty. |
| Schema versioning bookkeeping | Re-running raw DDL blindly each boot | A `schema_migrations` tracking table + `IF NOT EXISTS` DDL | Idempotent, ordered, auditable; survives restarts. ClickHouse has no transactional DDL so each statement must be independently idempotent (see §Pitfall: no transactional DDL). |

**Key insight:** ClickHouse is not Postgres. Every "obvious relational move" (per-row insert, UPDATE on completion, DELETE for retention, normalized join tables, transactional migration) is an anti-pattern here. The whole `storage/` layer is shaped to insert-only, batch, merge-on-read, TTL-for-retention semantics.

## client-v2 0.9.8 API Reference (the planner templates these calls)

**Build the client** `[CITED: clickhouse.com/docs/integrations/language-clients/java/client]`:
```kotlin
val client: Client = Client.Builder()
    .addEndpoint(endpointUrl)           // e.g. "http://clickhouse:8123/"
    .setUsername(user)
    .setPassword(password)              // from env var — never literal (CLAUDE.md)
    .compressClientRequest(true)        // gzip request bodies for fat payloads
    .build()
```

**Insert (batch, POJO list)** — register once, then insert:
```kotlin
// at startup, after migrations:
client.register(SpanRow::class.java, client.getTableSchema("spans"))
client.register(PayloadRow::class.java, client.getTableSchema("payloads"))

// per flush (suspend):
suspend fun flush(rows: List<SpanRow>) {
    val response: InsertResponse =
        client.insert("spans", rows, InsertSettings()).await()  // CompletableFuture<InsertResponse>.await()
    // response.metrics / response.queryId available
}
```
- `CompletableFuture<InsertResponse> insert(String table, List<?> data, InsertSettings settings)` — POJO list overload; client picks the most efficient (RowBinary) format. `[VERIFIED: clickhouse-java client-v2 docs]`
- `CompletableFuture<InsertResponse> insert(String table, InputStream data, ClickHouseFormat format[, InsertSettings])` — stream overload (use only if streaming raw RowBinary; the POJO overload is preferred).
- `void register(Class<?> clazz, TableSchema schema)` — must be called before the first insert for that POJO.
- `SpanRow`/`PayloadRow` are plain classes with getters mirroring the DDL column names/types (Nullable columns → nullable Kotlin/boxed types).

**Query (reads — Phase-4 consumers, but the repository seams are defined now)**:
```kotlin
suspend fun getTraceById(traceId: String): List<SpanRow> {
    // step 1: time range from the MV (STOR-02 path)
    val range = client.query(
        "SELECT MinTime, MaxTime FROM trace_id_ts FINAL WHERE TraceId = {tid:String}",
        mapOf("tid" to traceId), QuerySettings()
    ).await()
    // ... read MinTime/MaxTime via ClickHouseBinaryFormatReader
    // step 2: pruned range scan of spans
    val resp: QueryResponse = client.query(
        "SELECT * FROM spans WHERE TraceId = {tid:String} AND Timestamp BETWEEN {lo:DateTime64} AND {hi:DateTime64}",
        mapOf("tid" to traceId, "lo" to lo, "hi" to hi), QuerySettings()
    ).await()
    val reader: ClickHouseBinaryFormatReader = client.newBinaryFormatReader(resp)
    val out = mutableListOf<SpanRow>()
    while (reader.hasNext()) { reader.next(); out += reader.toSpanRow() } // getString/getLong/getDouble by column
    return out
}
```
- `CompletableFuture<QueryResponse> query(String sql)` / `query(String sql, Map<String,Object> params, QuerySettings)` — parameterized form binds `{name:Type}` placeholders (use it — never string-concat user input). `[VERIFIED: clickhouse-java client-v2 docs]`
- Read rows via `ClickHouseBinaryFormatReader` (`hasNext()`, `next()`, `getString(col)`, `getLong(col)`, `getDouble(col)`, `getBigInteger(col)`, ...) or the convenience `List<GenericRecord> queryAll(String sql)` for small result sets.
- **Payload lookup (STOR-03):** `SELECT InputPayload, OutputPayload, SystemInstructions, PayloadTruncated, PayloadOriginalSizeBytes FROM payloads WHERE TraceId={tid:String} AND SpanId={sid:String}` — single point lookup on the `(TraceId, SpanId)` sort key.

**Repository seam (return `Result<T>` for expected failures per ecosystem rule):**
```kotlin
interface SpanRepository {
    suspend fun writeBatch(spans: List<SpanRow>, payloads: List<PayloadRow>): Result<Unit>
    suspend fun getTraceById(traceId: String): Result<List<SpanRow>>
    suspend fun listRuns(filter: RunFilter): Result<List<AgentRunRow>>          // reads agent_runs
    suspend fun getPayload(traceId: String, spanId: String): Result<PayloadRow?>
}
```

## Startup-Applied Versioned Migration Runner

**Mechanism** (hand-rolled — the discretionary-but-constrained choice):
1. On startup, before the writer launches: `CREATE TABLE IF NOT EXISTS schema_migrations (Version UInt32, Name String, AppliedAt DateTime DEFAULT now()) ENGINE = MergeTree ORDER BY Version;`
2. List ordered resources `migrations/001_*.sql … 004_*.sql` (resource names sort lexically = apply order).
3. For each: `SELECT count() FROM schema_migrations WHERE Version = ?`. If `0`, execute the file's DDL (each statement already `CREATE ... IF NOT EXISTS`), then `INSERT INTO schema_migrations (Version, Name) VALUES (?, ?)`.
4. Execute statements one at a time (client-v2 sends a single statement per request; split multi-statement files on `;`).

**ClickHouse has no transactional DDL — what that means for safety:**
- There is **no rollback** if a multi-object migration file fails halfway. Mitigation: keep each DDL statement individually idempotent (`IF NOT EXISTS`) so a re-run after a partial failure is safe, and record a migration as applied **only after all its statements succeed** (so a partial failure re-runs the whole file, and the `IF NOT EXISTS` makes the already-created objects no-ops).
- Prefer **one object per migration file** (spans / payloads / trace_id_ts / agent_runs) to shrink the partial-failure blast radius.
- The `schema_migrations` insert is itself not transactional with the DDL; the `IF NOT EXISTS` + count-check makes the runner convergent (eventually-consistent and re-runnable) rather than atomic.
- `[CITED: Pitfall 10 — "Run schema migrations idempotently on tracer startup (CREATE TABLE IF NOT EXISTS)"]`

## Batching Write Pipeline (D-08)

**Pattern** (Channel + single writer coroutine; ARCHITECTURE.md Pattern 1, made concrete):
```kotlin
class SpanWriter(
    private val repo: SpanRepository,
    capacity: Int = 50_000,             // bounded — backpressure boundary
    private val batchSize: Int = 5_000, // flush by count
    private val flushInterval: Duration = 2.seconds, // flush by time
    private val maxRetries: Int = 4,
    scope: CoroutineScope,
) {
    private val intake = Channel<AgentSpan>(capacity)       // suspend-on-full = backpressure (D-08)
    val backpressure: StateFlow<Boolean> get() = _backpressure  // first-class signal for Phase-3

    suspend fun send(span: AgentSpan) = intake.send(span)  // producer suspends when full — never drops

    private val writer = scope.launch {
        val batch = ArrayList<AgentSpan>(batchSize)
        while (isActive) {
            // collect until batchSize OR flushInterval elapses (whichever first), then flush
            // on flush failure: retry up to maxRetries with exponential backoff (e.g. 100ms·2^n + jitter)
            // never drop the batch — exhausting retries keeps the buffer filling → producer suspends
        }
    }
    suspend fun drainAndClose() { intake.close(); writer.join() }  // graceful shutdown: flush on stop
}
```

**Key semantics:**
- **Backpressure (D-08):** a bounded `Channel(capacity)` makes `send` *suspend* when full — natural backpressure, no unbounded queue, no drop. Expose the saturated state as a `StateFlow<Boolean>` (or a suspend `send` whose suspension *is* the signal) so the Phase-3 receiver can translate it to OTLP `RESOURCE_EXHAUSTED`/`UNAVAILABLE`. `[CITED: ARCHITECTURE.md Pattern 1 trade-offs; kotlinx.coroutines Channel semantics]`
- **Flush trigger:** count (≥`batchSize`) OR time (≥`flushInterval`). Implement with a `select {}` over `intake.onReceive` and an `onTimeout` (the `whileSelect` sketch in ARCHITECTURE.md), or a simpler receive-with-timeout loop.
- **Retry:** bounded exponential backoff on `insert` failure (transient ClickHouse unavailability / `TOO_MANY_PARTS` self-throttle). After `maxRetries`, do **not** drop — let the buffer fill so backpressure propagates upstream (D-08).
- **Graceful shutdown (looks-done-but-isn't checklist):** `close()` the channel and `join()` the writer so the in-flight buffer flushes on container stop — otherwise spans are lost on `docker compose down`.
- **Thresholds env-configurable** with the above defaults (D-discretion).

## Common Pitfalls

### Pitfall 1: Per-request inserts → TOO_MANY_PARTS
**What goes wrong:** inserting per export/per span creates one part each; merges fall behind; ClickHouse rejects with `TOO_MANY_PARTS`. **How to avoid:** the Channel batcher is day-one architecture (≥5k rows or ≥2s). **Warning signs:** `system.parts` >300 active parts/partition; rising insert latency. `[CITED: PITFALLS.md Pitfall 1]`

### Pitfall 2: Wrong ORDER BY / partitioning
**What goes wrong:** `ORDER BY (TraceId,...)` kills compression + makes analytics full-scan; `ORDER BY (service,timestamp)` alone makes trace-open full-scan. **How to avoid:** low→high cardinality with `TraceId` last + `trace_id_ts` MV + bloom filter; `PARTITION BY toDate(Timestamp)`. **Warning signs:** poor compression ratio in `system.columns`; >100 partitions in a month of dev data. `[CITED: PITFALLS.md Pitfall 2]`

### Pitfall 3: Agent fields buried in the Map
**What goes wrong:** tokens/cost in `Map(String,String)` → every query deserializes the map + casts strings; aggregations sort `"0.05"` lexically. **How to avoid:** promoted typed columns (already in the DDL above). **Warning signs:** `Attributes['cost_usd']` casts in query SQL. `[CITED: PITFALLS.md Pitfall 3]`

### Pitfall 5/6 (schema-precluding risk): out-of-order/late traces + mutations
**What goes wrong (deferred to Phase 4 per D-07, but the schema must not preclude the fix):** assembling/finalizing runs at ingest breaks for late/orphaned spans; using `ALTER UPDATE` for run status stacks expensive mutations. **How to avoid now:** use AggregatingMergeTree (merge-friendly, max-wins reachable) for `agent_runs`; never write a mutation; retention via TTL only. **Warning signs:** any `ALTER TABLE ... UPDATE/DELETE`; any code that "finalizes" a trace N seconds after last span. `[CITED: PITFALLS.md Pitfalls 5, 6]`

### Pitfall: No transactional DDL in ClickHouse
**What goes wrong:** a multi-statement migration that fails halfway leaves partial schema with no rollback. **How to avoid:** one object per migration file; every statement `IF NOT EXISTS`; record applied-version only after all statements succeed; runner is convergent/re-runnable. **Warning signs:** migration file with 4 `CREATE`s and no `IF NOT EXISTS`. (See §Migration Runner.)

### Pitfall: Nullable on a sort-key column
**What goes wrong:** putting a `Nullable` metric in `ORDER BY` adds a null-map to the primary index and hurts compression/scan. **How to avoid:** the 5 Nullable metric columns (D-01) + `PayloadOriginalSizeBytes` are deliberately absent from every `ORDER BY`. **Warning signs:** `Nullable(...)` appearing inside an `ORDER BY (...)` tuple. `[CITED: clickhouse.com/docs/use-cases/observability/schema-design]`

## Code Examples

### Verifying granule pruning (STOR-02 — proves no full scan)
```sql
-- Source: clickhouse.com/docs EXPLAIN indexes; oneuptime ClickHouse EXPLAIN guide
EXPLAIN indexes = 1
SELECT * FROM spans
WHERE TraceId = '...' AND Timestamp BETWEEN '...' AND '...';
-- assert the plan reports Parts: x/N and Granules: g/G with g « G (pruned), not a full scan.
```

### Asserting bounded parts under sustained load (success criterion 4)
```sql
-- Source: clickhouse.com/docs partitions; active parts = not yet merged away
SELECT count() AS active_parts
FROM system.parts
WHERE table = 'spans' AND active = 1;
-- assert active_parts stays well under the too-many-parts threshold (e.g. < ~300) after N batched inserts.
```

### Testcontainers ClickHouse setup (JUnit 5)
```kotlin
// Source: java.testcontainers.org/modules/databases/clickhouse + Maven Central 1.21.4
import org.testcontainers.clickhouse.ClickHouseContainer  // NOTE: new package (not o.t.containers.*)
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class ClickHouseSchemaIT {
    companion object {
        @Container @JvmStatic
        val ch = ClickHouseContainer(
            DockerImageName.parse("clickhouse/clickhouse-server:26.3")  // pin LTS, never :latest
        )
    }
    // ch.host / ch.getMappedPort(8123) → build a client-v2 Client against the container;
    // run MigrationRunner; assert all 4 objects exist in system.tables; EXPLAIN-prune; insert/query round-trip.
}
```

## Runtime State Inventory

> Greenfield storage layer — no rename/refactor. This phase adds the first `storage/` code and the first ClickHouse dependency; nothing pre-existing stores the relevant strings.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — no ClickHouse instance/schema exists yet (this phase creates it) | none |
| Live service config | None — no running services reference this schema | none |
| OS-registered state | None | none |
| Secrets/env vars | New: `CLICKHOUSE_URL` / `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD` to be read from env (CLAUDE.md: no secrets in code). Not yet defined anywhere. | define env-var contract; Compose wiring is Phase 4 (DIST-01) |
| Build artifacts | None — `storage/` package and migration resources are new | none |

**Nothing found in any category — verified by inspecting `backend/` (only `domain/` + `convention/` packages exist) and the absence of any ClickHouse dependency in `gradle/libs.versions.toml`.**

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `clickhouse-jdbc` for inserts/queries | `com.clickhouse:client-v2` (RowBinary, async, POJO register) | client-v2 GA'd in the 0.7→0.9 line | Cleaner API, RowBinary throughput, native `CompletableFuture` → coroutine bridge. Official new-app recommendation. |
| `org.testcontainers.containers.ClickHouseContainer` (old package) | `org.testcontainers.clickhouse.ClickHouseContainer` (new package) | testcontainers ~1.20+ | Import path changed; the old package is deprecated. Use the new `org.testcontainers.clickhouse.*`. |
| `kotlinx-coroutines-jdk8` artifact for `CompletableFuture.await()` | `kotlinx.coroutines.future.await()` in `kotlinx-coroutines-core` | coroutines 1.7+ | `-jdk8` is now an empty back-compat stub; depend on `-core` only. |
| Ingest-time trace finalization / mutations for status | Insert-only + merge-friendly engines + TTL | ClickHouse best practice (ongoing) | No mutations; AggregatingMergeTree rollup; TTL retention. |

**Deprecated/outdated (do not use):**
- `ru.yandex.clickhouse` / client-v1 API — legacy, deprecated by ClickHouse Inc.
- `clickhouse/clickhouse-server:latest` — non-reproducible; pin `:26.3`.
- `SummingMergeTree` for `agent_runs` — can't express max(saturation)/min/max timing; use AggregatingMergeTree.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`junit-jupiter` 5.14.4) + Kotest assertions (6.1.11) + MockK (1.14.11) + Testcontainers (1.21.4) |
| Config file | `backend/build.gradle.kts` (`tasks.test { useJUnitPlatform() }` already present) |
| Quick run command | `./gradlew :backend:test --tests "*SpanWriterTest"` (unit, no container) |
| Full suite command | `./gradlew :backend:test` (includes `*IT` Testcontainers integration tests) |

### Phase Requirements → Test Map
| Req / Criterion | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| STOR-01 / SC1 | Spans land with typed first-class agent columns, directly SQL-queryable (not in Map) | integration | `./gradlew :backend:test --tests "*SpanRepositoryIT"` | ❌ Wave 0 |
| STOR-01 | `agent_runs` rollup populates (summed tokens/cost, max saturation, span count) for complete in-order traces + dedup (D-07) | integration | `./gradlew :backend:test --tests "*SpanRepositoryIT"` | ❌ Wave 0 |
| STOR-02 / SC2 | Trace-by-id via `trace_id_ts` MV path avoids full scan (EXPLAIN shows granule pruning) | integration | `./gradlew :backend:test --tests "*ClickHouseSchemaIT"` | ❌ Wave 0 |
| STOR-03 / SC3 | Payloads stored + retrieved individually by `(TraceId, SpanId)`; truncation metadata round-trips | integration | `./gradlew :backend:test --tests "*SpanRepositoryIT"` | ❌ Wave 0 |
| SC4 | Batched writes (count/time flush) keep `system.parts` bounded under sustained load | integration | `./gradlew :backend:test --tests "*PartCountIT"` | ❌ Wave 0 |
| D-08 | Bounded buffer suspends producer (backpressure), not drop; flush thresholds honored | unit (MockK fake repo) | `./gradlew :backend:test --tests "*SpanWriterTest"` | ❌ Wave 0 |
| Migrations | Idempotent re-run is a no-op; `schema_migrations` tracks versions; all 4 objects in `system.tables` | integration | `./gradlew :backend:test --tests "*ClickHouseSchemaIT"` | ❌ Wave 0 |

**Observable signals proving each success criterion:**
- **SC1 (typed columns):** `SELECT CostUsd, InputTokens, ContextSaturationPct, SkillName, Model FROM spans WHERE ...` returns typed values; `EXPLAIN` on a cost-range filter reads the typed column, not `SpanAttributes[...]`.
- **SC2 (MV no-scan):** `EXPLAIN indexes = 1` on the trace-by-id query reports `Granules: g/G` with g « G; the `trace_id_ts` MV returns the time range for the trace.
- **SC3 (payload round-trip):** insert a payload (incl. a truncated one), read it back by `(TraceId, SpanId)`, assert bytes + `PayloadTruncated`/`PayloadOriginalSizeBytes` match.
- **SC4 (bounded parts):** after writing many batches through the pipeline, `SELECT count() FROM system.parts WHERE table='spans' AND active=1` stays under threshold; per-row insert (control) would explode it.

### Sampling Rate
- **Per task commit:** `./gradlew :backend:test --tests "*SpanWriterTest"` (fast unit; no Docker).
- **Per wave merge:** `./gradlew :backend:test` (full suite incl. Testcontainers — requires Docker; CI self-hosted runners have it).
- **Phase gate:** full suite green + `ktlintCheck` before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `ClickHouseSchemaIT.kt` — DDL applies, idempotent re-run, EXPLAIN pruning — covers STOR-02 + migrations
- [ ] `SpanRepositoryIT.kt` — insert/query round-trip, payload round-trip, agent_runs rollup — covers STOR-01 + STOR-03
- [ ] `PartCountIT.kt` — sustained batched insert keeps `system.parts` bounded — covers SC4
- [ ] `SpanWriterTest.kt` — flush thresholds + backpressure with MockK fake repo — covers D-08
- [ ] Shared Testcontainers base (single `ClickHouseContainer` reused across IT classes) + a `SpanFixtures`-style row builder
- [ ] Catalog additions (client-v2, coroutines-core, testcontainers-clickhouse, testcontainers-junit, kotlin-logging, logback) + `backend/build.gradle.kts` wiring

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker daemon | Testcontainers integration tests | ✓ (CI self-hosted runners; assume present locally) | — | None for IT — unit tests (`SpanWriterTest`) run without Docker |
| `clickhouse/clickhouse-server:26.3` image | Testcontainers + Phase-4 Compose | Pulled on first test run | 26.3 LTS | None — pin required |
| JVM 21 toolchain | Build/test | ✓ (`jvmToolchain(21)` in build) | 21 | — |
| Gradle | Build | ✓ (wrapper) | 9.5.1 (STACK.md) | — |

**Missing dependencies with no fallback:** Docker is required for the `*IT` Testcontainers tests; if a runner lacks Docker, those tests must be gated (e.g. `@EnabledIfDockerAvailable`) but the success criteria explicitly require Testcontainers, so CI must provide Docker.
**Missing dependencies with fallback:** none.

## Security Domain

> `security_enforcement: true`, ASVS level 1. This phase is a storage/data layer (no auth, no session, no HTTP surface — those are Phase 3/4).

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | No auth surface in storage layer (Phase 3/4 receivers/API) |
| V3 Session Management | no | — |
| V4 Access Control | no | Single-team self-hosted; no multi-tenancy (REQUIREMENTS Out of Scope) |
| V5 Input Validation | yes | Parameterized client-v2 queries (`{name:Type}` placeholders) — never string-concat SQL; payload 256 KB cap honored at write (Phase-1 D-13) |
| V6 Cryptography | no | No crypto in storage; ClickHouse credentials from env vars (never literal) |
| V7 Error Handling/Logging | yes | `Result<T>` for expected failures; log flush/retry/backpressure without leaking payload contents |

### Known Threat Patterns for {ClickHouse storage layer}
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SQL injection via trace_id / filter values | Tampering | client-v2 parameterized queries (`{tid:String}`), never string interpolation |
| Credentials in source/config | Information Disclosure | `CLICKHOUSE_PASSWORD` etc. from env vars only (CLAUDE.md hard rule) |
| Sensitive prompt/PII in payloads persisted long-term | Information Disclosure | TTL (14-day default) as retention mitigation; payloads off the hot path; truncation cap; convention already warns payloads may contain PII (Phase-1 §Security) |
| Unbounded payload poisoning a batch | DoS | 256 KB per-field cap enforced at write (Phase-1 D-13); bounded buffer (D-08) |
| Disk exhaustion via unbounded growth | DoS | mandatory `TTL` clause in every table DDL (Pitfall 6) |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `agent_runs` uses `AggregatingMergeTree` + `SimpleAggregateFunction` (resolving D-06) | Architecture Decision 1 / Pattern 3 | Low — both AggregatingMergeTree and SimpleAggregateFunction are HIGH-confidence verified; the *choice between* AMT and SummingMergeTree is reasoned (max-wins need), not arbitrary. If wrong, it's a query-shape change, not a table rewrite (engine is still merge-friendly). |
| A2 | `kotlin-logging-jvm` 8.0.4 / `logback-classic` 1.5.34 exact versions | Standard Stack (Supporting) | Low — confirm against Maven Central at install; logging is non-load-bearing. |
| A3 | `client.getTableSchema("spans")` is the schema-fetch method name for `register()` | client-v2 API Reference | Low — the register-with-schema pattern is verified; exact method name should be confirmed against the 0.9.8 javadoc during planning. |
| A4 | `client.newBinaryFormatReader(response)` is the reader-construction call | client-v2 API Reference | Low — `ClickHouseBinaryFormatReader` is verified as the reader class; the exact factory method name confirmed at implementation. |
| A5 | 14-day default TTL | DDL | None — explicitly discretionary (D-discretion); overridable. |

**These are the only `[ASSUMED]` items; everything else is `[VERIFIED]` or `[CITED]`.** The planner should add a quick javadoc confirmation step for A3/A4 (exact method names) but they do not block schema/pipeline design.

## Open Questions

1. **Exact client-v2 0.9.8 method names for `getTableSchema` / binary reader construction (A3/A4).**
   - What we know: `register(Class, TableSchema)`, `insert(table, List<?>, InsertSettings)`, `query(...)`, `ClickHouseBinaryFormatReader`, `GenericRecord` are all verified API surface.
   - What's unclear: the precise factory method names (`getTableSchema` vs `getTableSchemaFromQuery`; `newBinaryFormatReader` vs `getReader`).
   - Recommendation: confirm against the 0.9.8 javadoc or the `clickhouse-java/examples/client-v2` sources during planning of the repository task; does not affect schema or pipeline design.

2. **`agent_runs` Outcome semantics for incomplete traces (intentionally deferred).**
   - What we know: D-07 scopes Phase-2 tests to complete/in-order traces; AggregatingMergeTree keeps max-wins reachable.
   - What's unclear: the exact late/never-arriving-span merge rule (Phase 4).
   - Recommendation: do not solve now; ensure tests only assert complete-trace correctness so Phase 4 can extend without contradicting Phase-2 tests.

## Key Architecture-Affecting Decisions

### Decision 1: `agent_runs` engine = AggregatingMergeTree + SimpleAggregateFunction (resolves D-06)
`SummingMergeTree` only collapses additively — it has no way to express `max(ContextSaturationPct)`, `min(StartTime)`, `max(EndTime)`, or `anyLast(Outcome)`, all of which D-06 lists and all of which are the merge-friendly max-wins semantics Pitfall 5 wants preserved for the Phase-4 late-span fix. `AggregatingMergeTree` with `SimpleAggregateFunction(sum|min|max|anyLast, ...)` expresses every column in one engine, and `SimpleAggregateFunction` (vs full `AggregateFunction`) stores the final value so reads need no `finalizeAggregation`/`-Merge` gymnastics — only an occasional `FINAL` or `GROUP BY` to collapse concurrent partial states. `[VERIFIED: clickhouse.com/docs/engines/table-engines/mergetree-family/aggregatingmergetree + Altinity SimpleAggregateFunction KB]`

### Decision 2: Explicit TARGET tables for MVs (not implicit `.inner.`)
Defining `trace_id_ts` / `agent_runs` as explicit tables with their own `ENGINE`/`ORDER BY`/`TTL`, then a `... TO target` MV, makes the engine choice, sort key, and TTL first-class and testable, and lets the lookup/rollup tables expire in lockstep with `spans`. Implicit-storage MVs hide these unrecoverable choices behind a generated `.inner.` table.

### Decision 3: Payloads in a separate table (D-04), same TTL (D-05)
Keeps `spans` lean for the hot analytical/scan path; payloads are point-lookup only `(TraceId, SpanId)`. High-level `ZSTD(3)` on payload columns (text compresses well) vs `ZSTD(1)` on the scan-hot `spans` columns. Same 14-day TTL on both so no orphaned blobs.

### Decision 4: client-v2 (not JDBC), POJO register + async future bridge
RowBinary batch inserts for ingest throughput; `CompletableFuture.await()` keeps the pipeline non-blocking per the coroutines-only rule; parameterized queries prevent SQL injection. JDBC is the alternative only if JDBC-ecosystem tooling is later needed.

## Sources

### Primary (HIGH confidence)
- repo1.maven.org maven-metadata (client-v2 0.9.8, testcontainers:clickhouse 1.21.4, kotlinx-coroutines-core/jdk8 1.11.0) — fetched 2026-06-21, authoritative registry
- [ClickHouse Java client docs (client-v2 API: Client.Builder, insert, query, register, readers)](https://clickhouse.com/docs/integrations/language-clients/java/client)
- [ClickHouse: Storing Traces and Spans in ClickHouse (schema, ORDER BY, trace_id_ts MV, bloom filter)](https://clickhouse.com/blog/storing-traces-and-spans-open-telemetry-in-clickhouse)
- [ClickHouse: Designing a schema for observability (avoid Nullable on sort/index cols, materialized columns)](https://clickhouse.com/docs/use-cases/observability/schema-design)
- [ClickHouse: AggregatingMergeTree engine](https://clickhouse.com/docs/engines/table-engines/mergetree-family/aggregatingmergetree)
- [ClickHouse: Table partitions / system.parts active parts](https://clickhouse.com/docs/partitions)
- [Testcontainers Java — ClickHouse module (org.testcontainers.clickhouse.ClickHouseContainer)](https://java.testcontainers.org/modules/databases/clickhouse/)
- [.planning/research/PITFALLS.md](.planning/research/PITFALLS.md) — Pitfalls 1,2,3,5,6,10 (HIGH, verified against ClickHouse docs)
- [.planning/research/ARCHITECTURE.md](.planning/research/ARCHITECTURE.md) — Pattern 1 (Channel batcher), Pattern 2 (wide table + MVs), verified schema sketch
- [.planning/research/STACK.md (embedded in CLAUDE.md)](CLAUDE.md) — client-v2 0.9.8, ClickHouse 26.3 LTS, testcontainers 1.21.4, coroutines 1.11.0

### Secondary (MEDIUM confidence)
- [Altinity KB: SimpleAggregateFunction & combinators](https://kb.altinity.com/altinity-kb-queries-and-syntax/simplestateif-or-ifstate-for-simple-aggregate-functions/)
- [kotlinlang.org API: kotlinx.coroutines.future.await (CompletionStage.await)](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.future/await.html)
- [clickhouse-java examples/client-v2 (Stream2DbWriter, register pattern)](https://github.com/ClickHouse/clickhouse-java/tree/main/examples/client-v2)
- EXPLAIN indexes / granule pruning, system.parts active-count verification (cross-checked ClickHouse docs + oneuptime guides)

### Tertiary (LOW confidence)
- kotlin-logging-jvm / logback exact versions (A2) — confirm at install

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all versions verified on Maven Central 2026-06-21; client-v2 is official recommendation
- Schema/DDL: HIGH — consensus design verified against ClickHouse official docs + ARCHITECTURE.md; D-06 engine resolved with cross-checked reasoning
- client-v2 API surface: HIGH for the core insert/query/register methods; MEDIUM for two exact factory method names (A3/A4)
- Pipeline/backpressure: HIGH — Channel semantics + ARCHITECTURE.md Pattern 1
- Migrations: HIGH (idempotent IF NOT EXISTS) / the no-transactional-DDL caveat is a documented ClickHouse property
- Validation: HIGH — EXPLAIN pruning + system.parts are the documented verification tools

**Research date:** 2026-06-21
**Valid until:** 2026-07-21 (30 days — ClickHouse server/client and Testcontainers are stable lines; re-verify client-v2 patch version at planning if later)
