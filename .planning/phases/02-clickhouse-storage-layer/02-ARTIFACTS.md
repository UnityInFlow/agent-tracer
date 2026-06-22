# Phase 2 — Artifacts This Phase Produces

> Every symbol/file the four plans create. Downstream phases (3 ingestion, 4 query API) consume the **bold** seams.

## Span-context carrier (Plan 02-01) — the OTLP-envelope identity seam

| Symbol | File | Public surface |
|--------|------|----------------|
| **`SpanContext`** | `storage/SpanContext.kt` | immutable `data class SpanContext(traceId: String, spanId: String, parentSpanId: String, timestamp: Instant)` — the OTLP-envelope identity `AgentSpan` deliberately omits (attributes-only). Sole source of the mandatory `TraceId`/`SpanId`/`ParentSpanId`/`Timestamp` schema columns. Phase 2 mints it via fixtures; **the Phase-3 receiver populates it from the real OTLP span envelope**. |

## DDL resources (Plan 02-02)

| Path | Object(s) created |
|------|-------------------|
| `backend/src/main/resources/migrations/001_spans.sql` | `spans` (MergeTree, wide typed-column table; identity cols Timestamp/TraceId/SpanId/ParentSpanId) + `idx_trace_id` bloom-filter skip index |
| `backend/src/main/resources/migrations/002_payloads.sql` | `payloads` (MergeTree, keyed `(TraceId, SpanId)`) |
| `backend/src/main/resources/migrations/003_trace_id_ts_mv.sql` | `trace_id_ts` (AggregatingMergeTree target) + `trace_id_ts_mv` materialized view |
| `backend/src/main/resources/migrations/004_agent_runs_mv.sql` | `agent_runs` (AggregatingMergeTree target) + `agent_runs_mv` materialized view |
| (runtime) | `schema_migrations` tracking table created by `MigrationRunner` |

## Config + connection (Plan 02-02)

| Symbol | File | Public surface |
|--------|------|----------------|
| `ClickHouseConfig` | `storage/ClickHouseConfig.kt` | data class (url/database/user/password) + `fromEnv()`; password env-var only |
| `ClickHouseClientFactory` | `storage/ClickHouseClientFactory.kt` | builds a client-v2 `Client` from `ClickHouseConfig`; lifecycle close |
| `MigrationRunner` | `storage/migrations/MigrationRunner.kt` | `suspend fun runMigrations(): Result<Unit>` — ordered idempotent DDL apply |

## Repository seam (Plan 02-03) — consumed by Phase 4 query API

| Symbol | File | Public methods |
|--------|------|----------------|
| **`SpanRepository`** | `storage/SpanRepository.kt` | `suspend writeBatch(spans, payloads): Result<Unit>` · `suspend getTraceById(traceId): Result<List<SpanRow>>` · `suspend listRuns(filter: RunFilter): Result<List<AgentRunRow>>` · `suspend getPayload(traceId, spanId): Result<PayloadRow?>` |
| `ClickHouseSpanRepository` | `storage/ClickHouseSpanRepository.kt` | `SpanRepository` impl (RowBinary insert, two-step trace lookup, parameterized reads) |
| `SpanRow` | `storage/SpanRow.kt` | client-v2 register POJO mirroring `spans` columns (incl. SpanContext-sourced identity Timestamp/TraceId/SpanId/ParentSpanId) |
| `PayloadRow` | `storage/PayloadRow.kt` | POJO mirroring `payloads` columns (TraceId/SpanId/Timestamp from SpanContext) |
| `AgentRunRow` | `storage/AgentRunRow.kt` | read model for `agent_runs` rollup |
| `RunFilter` | `storage/RunFilter.kt` | immutable filter (agent, model, cost range, outcome, date range) |

## Write pipeline (Plan 02-04) — consumed by Phase 3 receiver

| Symbol | File | Public surface |
|--------|------|----------------|
| **`SpanWriter`** | `storage/pipeline/SpanWriter.kt` | `suspend fun send(span: AgentSpan, context: SpanContext)` (carries envelope identity; suspends on full = backpressure) · `suspend fun drainAndClose()` · `val backpressure: StateFlow<Boolean>` |
| **`BackpressureSignal`** | `storage/pipeline/BackpressureSignal.kt` | read-only `StateFlow<Boolean> saturated` — the Phase-3 receiver observes this to map to OTLP `RESOURCE_EXHAUSTED`/`UNAVAILABLE` |
| `AgentSpanRowMapper` | `storage/AgentSpanRowMapper.kt` | `map(span: AgentSpan, context: SpanContext): Pair<SpanRow, PayloadRow>` — identity from SpanContext + 256 KB cap + truncation enforced at write time |

## Test infrastructure (Plan 02-01) + per-plan ITs

| Path | Purpose |
|------|---------|
| `…/test/…/storage/ClickHouseTestSupport.kt` | shared singleton ClickHouse 26.3 container + `newClient()` |
| `…/test/…/storage/SpanFixtures.kt` | deterministic `(AgentSpan, SpanContext)` builders (full/minimal/truncatedPayload) + `traceOf(n, …)` minting N spans on one TraceId with uniform agentName/model |
| `…/test/…/storage/ClickHouseSchemaIT.kt` | DDL apply, idempotent re-run, EXPLAIN pruning (STOR-02) |
| `…/test/…/storage/SpanRepositoryIT.kt` | round-trips, payload retrieval, rollup + dedup (STOR-01/03) |
| `…/test/…/storage/AgentSpanRowMapperTest.kt` | identity-from-context, cap/truncation/null-mapping unit |
| `…/test/…/storage/pipeline/SpanWriterTest.kt` | flush/backpressure/no-drop/drain unit (MockK) |
| `…/test/…/storage/PartCountIT.kt` | bounded `system.parts` under sustained load with real multi-partition identity (SC4) |

## New env-var contract (Compose wiring is Phase 4 / DIST-01)

`CLICKHOUSE_URL` · `CLICKHOUSE_USER` · `CLICKHOUSE_PASSWORD` · `CLICKHOUSE_DATABASE` — read via `ClickHouseConfig.fromEnv()`, never literal in source.

---

# Multi-Source Coverage Audit

Every source item is COVERED by a plan. No MISSING items → finalized.

## GOAL (ROADMAP Phase 2 goal + 4 success criteria)

| Source item | Covered by | Status |
|-------------|-----------|--------|
| Goal: spans persist serving trace lookup / run-list / replay at scale, unrecoverable schema decisions made up front | 02-02 (schema) + 02-03 (repo) + 02-04 (pipeline) | COVERED |
| SC1: typed first-class agent columns directly queryable | 02-02 DDL + 02-03 IT round-trip | COVERED |
| SC2: trace-by-id via MV path, no full scan (EXPLAIN) | 02-02 ClickHouseSchemaIT (EXPLAIN pruning) | COVERED |
| SC3: payloads stored + retrieved individually | 02-02 payloads DDL + 02-03 SpanRepositoryIT | COVERED |
| SC4: batching keeps part counts bounded, Testcontainers | 02-04 PartCountIT | COVERED |

## REQ (REQUIREMENTS.md phase req ids)

| Req | Covered by | Status |
|-----|-----------|--------|
| STOR-01 (schema: spans/traces/agent_runs typed columns) | 02-02 DDL + 02-03 IT | COVERED |
| STOR-02 (trace lookup via MV, no full scan) | 02-02 trace_id_ts MV + ClickHouseSchemaIT | COVERED |
| STOR-03 (payloads stored, replay/detail-panel) | 02-02 payloads + 02-03/02-04 (mapper truncation) | COVERED |

## RESEARCH (RESEARCH.md features/constraints)

| Research item | Covered by | Status |
|---------------|-----------|--------|
| Four-object schema (spans + trace_id_ts MV + agent_runs MV + payloads) | 02-02 | COVERED |
| AggregatingMergeTree + SimpleAggregateFunction for agent_runs (resolves D-06) | 02-02 004_agent_runs_mv.sql | COVERED |
| client-v2 register/insert/query + CompletableFuture.await() bridge | 02-03 ClickHouseSpanRepository | COVERED |
| Hand-rolled versioned startup MigrationRunner + schema_migrations | 02-02 MigrationRunner | COVERED |
| Channel-based batching pipeline with retry-then-backpressure | 02-04 SpanWriter | COVERED |
| Span-context envelope identity seam for Phase-3 receiver | 02-01 SpanContext (threaded through 02-03 rows / 02-04 mapper+writer) | COVERED |
| Testcontainers verification (EXPLAIN pruning, system.parts) | 02-02 / 02-04 ITs | COVERED |
| Validation Architecture (Wave 0 gaps, test map) | 02-01 + 02-VALIDATION.md | COVERED |

## CONTEXT (D-01..D-08 locked decisions)

| Decision | Covered by | Status |
|----------|-----------|--------|
| D-01 Nullable only on metric columns, out of ORDER BY | 02-02 001_spans.sql | COVERED |
| D-02 sentinel defaults for dimensions | 02-02 001_spans.sql + 02-04 mapper | COVERED |
| D-03 boolean flags UInt8 / nullable original-size | 02-02 DDL + 02-04 mapper | COVERED |
| D-04 separate payloads table keyed (TraceId, SpanId) | 02-02 002_payloads.sql + 02-03 getPayload | COVERED |
| D-05 same TTL as spans | 02-02 002_payloads.sql | COVERED |
| D-06 agent_runs incremental AggregatingMergeTree MV | 02-02 004_agent_runs_mv.sql | COVERED |
| D-07 tests scoped to complete/in-order + dedup; defer late-merge | 02-03 SpanRepositoryIT assertions | COVERED |
| D-08 bounded retry then backpressure, first-class signal | 02-04 SpanWriter + BackpressureSignal | COVERED |

**Excluded (not gaps):** OTLP receivers (Phase 3 — also supplies real `SpanContext` envelope values), query REST API / DAG assembly (Phase 4), React frontend (Phase 5), deep late/out-of-order replay-merge (deferred to Phase 4 per D-07), disk-spill/WAL durability + async_insert (Deferred Ideas).
</content>
