# Phase 2: ClickHouse Storage Layer - Context

**Gathered:** 2026-06-21
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers the storage layer that persists `AgentSpan`s into ClickHouse so the tool can serve trace-by-id lookup, run-list filtering, and replay at time-series scale. It covers: the `spans` table schema (typed first-class agent columns + raw attribute maps), the `trace_id_ts` materialized view for point lookup, the `agent_runs` rollup MV, a separate payloads table, a client-side batching write pipeline, startup-applied versioned DDL migrations, and Testcontainers integration tests against real ClickHouse.

**Out of scope (their own phases):** OTLP receivers / gRPC + HTTP endpoints and receiver-side backpressure wiring (Phase 3); the query REST API, server-side DAG assembly, and the run-list/replay consumers that read these tables (Phase 4); the React frontend (Phase 5). This phase makes the *unrecoverable schema decisions* (ORDER BY, typed columns, payload placement, rollup engine, TTL) correctly up front and proves writes/reads work against a real ClickHouse — it does not build the things that consume the data over HTTP.

Requirements: STOR-01, STOR-02, STOR-03.

</domain>

<decisions>
## Implementation Decisions

### Null semantics (reconciling D-12 with ClickHouse guidance)
- **D-01:** Use ClickHouse `Nullable(...)` **only on the aggregate-feeding metric columns** — cost (`Nullable(Float64)`), input/output tokens (`Nullable(UInt64)`), saturation (`Nullable(Float32)`), and plan step (`Nullable(Int64)`). The product premise is cost/token/saturation aggregation, so "unmeasured" must stay distinguishable from a real `0` (Phase-1 D-12: absence is information; aggregates skip nulls). These columns are deliberately kept **out of the ORDER BY / primary key** so the Nullable overhead is limited and never touches the sort key.
- **D-02:** Every other column uses **sentinel defaults, not Nullable** — empty string / `LowCardinality(String)` for dimensional string fields (agent, model, skill, provider, ids, outcome, operation, status), `''` for absent strings, default `[]` for `Array(String)` (finish reasons). This follows ClickHouse's "avoid Nullable" guidance where empty-string is a perfectly good "absent" and Nullable would be most costly (high-cardinality / sort-key-adjacent columns).
- **D-03:** Boolean-ish flags (e.g. `payload.truncated`) map to `UInt8` with `0` default; `payloadOriginalSizeBytes` is `Nullable(UInt64)` only if absence must be distinguished, otherwise `0` default — planner's discretion, but stay consistent with the D-01/D-02 split (numeric-that-feeds-a-decision → Nullable; descriptive → default).

### Payload storage placement
- **D-04:** Store input/output/system payloads in a **separate payloads table** keyed by `(TraceId, SpanId)`, NOT inline in the `spans` table. Keeps the `spans` table lean for the hot analytical path (run-list filters, cost queries, scans); payloads are fetched only as point lookups for replay and the span detail panel (satisfies STOR-03's "retrieved individually"). 256 KB-per-field cap (Phase-1 D-13) and the `unityinflow.payload.truncated` / original-size contract are honored at write time.
- **D-05:** The payloads table uses the same TTL window as `spans` so blobs expire in lockstep — no orphaned payloads, no separate retention policy to reason about.

### agent_runs rollup
- **D-06:** Build the `agent_runs` per-trace rollup as an **incremental `AggregatingMergeTree` (or `SummingMergeTree`) materialized view in this phase** — keyed by root span / `TraceId`, with agent, model, start, duration, summed tokens, summed cost, max saturation, outcome, and span count as columns. The engine choice + ORDER BY are unrecoverable, so they are decided and pinned now with research; an incremental MV self-populates on insert (it's DDL, not runtime wiring), so this honestly satisfies STOR-01's "stores … agent_runs."
- **D-07:** **Scope the Phase-2 tests** to rollup correctness for complete / in-order traces plus span dedup. The deep **late / out-of-order / incomplete-trace replay-merge** edge cases (PITFALLS Pitfall 5/6 — max-wins merge, never-arriving subagent spans) are deferred to Phase 4, where the replay/query consumer is designed and PITFALLS explicitly flags them for deeper research. Don't over-invest in replay-merge correctness before a consumer exists — but don't let the schema preclude it (use merge-friendly engines now).

### Write-failure & backpressure policy
- **D-08:** The write pipeline is **bounded retry, then backpressure** — never drop, never queue unboundedly. A bounded coroutine `Channel` accumulates spans; the writer coroutine flushes by count (~5k) or time (~2s); a failed flush retries a few times with exponential backoff. When the bounded buffer is full, the producer **suspends** (backpressure). The storage layer exposes this backpressure signal as a first-class part of its API so the Phase-3 receiver can translate it to OTLP `RESOURCE_EXHAUSTED` / `UNAVAILABLE` (per PITFALLS Pitfall 4) rather than dropping or unbounded-queuing. Dropping is rejected: losing traces in a debugging tool defeats its purpose. Disk-spill/WAL durability is rejected as over-built for v0.0.1 Docker Compose.

### Claude's Discretion
- Exact column naming/casing in DDL (follow the ClickHouse otel-exporter / ARCHITECTURE.md schema sketch conventions), `CODEC` choices (`ZSTD`, `Delta`), bloom-filter granularity, and partition granularity (`toDate(Timestamp)` per the sketch).
- Migration runner mechanism (hand-rolled versioned DDL applied at startup vs a tiny library) — startup-applied + versioned is the constraint; the how is open.
- Exact flush thresholds and retry/backoff counts, and whether they are env-configurable (a sensible default that matches the research ranges is fine).
- Repository/DAO API shape, Kotlin package layout under `storage/`, coroutine bridging of `client-v2` `CompletableFuture`s via `.await()`.
- Default TTL duration (research shows 14–30 days; pick a sane default, make it overridable) — the *existence* of a TTL clause in v0.0.1 DDL is mandatory, the exact number is discretionary.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope & requirements
- `.planning/REQUIREMENTS.md` — STOR-01, STOR-02, STOR-03 definitions
- `.planning/ROADMAP.md` — Phase 2 goal and the 4 success criteria (typed columns, MV trace lookup, payload store/retrieve, batching proven via Testcontainers)

### Research (directly load-bearing for this phase)
- `.planning/research/PITFALLS.md` — **the single most important ref for this phase.** Pitfall 1 (per-request INSERT → too-many-parts; client-side batching mandatory), Pitfall 2 (ORDER BY / partitioning; two-table pattern; avoid Nullable on sort/index cols), Pitfall 3 (promote agent fields to typed columns, keep raw Map), Pitfall 5 (out-of-order/late/incomplete traces — don't assemble at ingest; merge-friendly engines), Pitfall 6 (no mutations — ReplacingMergeTree + insert-new-version; TTL not DELETE).
- `.planning/research/ARCHITECTURE.md` — Pattern 2 (one wide `spans` table + `trace_id_ts` and `agent_runs` MVs), the verified schema sketch (CREATE TABLE spans … ORDER BY (ServiceName, SpanName, toUnixTimestamp(Timestamp), TraceId)), MV definitions, and the storage/ package seam.
- `.planning/research/STACK.md` — `com.clickhouse:client-v2:0.9.8` (RowBinary streaming inserts; recommended over JDBC), ClickHouse server `26.3` LTS (pin in Compose, not `latest`), `org.testcontainers:clickhouse:1.21.4`, kotlinx-coroutines `.await()` bridge, Kotlin 2.4.0 / Gradle 9.5.1 / JUnit 5.14.4 / Kotest 6.1.11 / MockK.

### Upstream contract (this phase persists its output)
- `docs/agent-span-convention.md` — the published v1.0 attribute contract; the per-attribute OTLP value types gate the column types.
- `backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt` — the typed domain model the schema must mirror (nullable fields → D-01/D-02 column mapping).
- `.planning/phases/01-attribute-convention-domain-model/01-CONTEXT.md` — Phase-1 decisions, esp. D-12 (nullable = absent), D-13 (256 KB payload cap + truncation), D-08 (trace = run).

### Ecosystem rules
- `CLAUDE.md` — Kotlin constraints (no `var`, no `!!` w/o comment, coroutines for async, JUnit 5 + Kotest, ktlint), group `io.github.unityinflow`, self-hosted CI runners.
- `09-agent-tracer.md` — original feature spec.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `backend/` Gradle module exists (Phase 1) with `domain/` and `convention/` packages. This phase adds a `storage/` package alongside them — first code that imports the ClickHouse client. No storage code exists yet (greenfield storage).
- `AgentSpan` (typed, nullable-field immutable data class) is the exact write-model the repository serializes into ClickHouse rows. `AgentOutcome`, `GenAiOperation` enums map to `LowCardinality(String)` / Enum columns.

### Established Patterns
- Ecosystem Kotlin conventions: Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`), immutable data classes, sealed classes, `Result<T>` over exceptions for expected failures, coroutines for all async, ktlint.
- Phase-1 test rigor (37 backend tests, exhaustive table-driven coverage) is the bar; storage adds Testcontainers integration tests for DDL + insert/query round-trips.

### Integration Points
- The batching write pipeline's **producer-facing API** (a suspend `send`/channel with backpressure) is the seam the Phase-3 OTLP receiver will plug into — design it transport-agnostic.
- The repository's **query methods** (trace-by-id via `trace_id_ts`, run-list over `agent_runs`, payload-by-span) are the seam the Phase-4 query API + DAG assembler will read through. Nothing outside `storage/` touches ClickHouse SQL.

</code_context>

<specifics>
## Specific Ideas

- Lean on the documented ClickHouse otel-collector / SigNoz / Jaeger-v2 two-table consensus design (per ARCHITECTURE.md Pattern 2) rather than inventing a schema — the research has already verified it against ClickHouse's official observability guide.
- "Proven against a real ClickHouse instance via Testcontainers" (success criterion 4) is the acceptance bar: schema applies, batched inserts land, trace-by-id avoids a full scan (verify via `EXPLAIN`/part-pruning), payloads round-trip, and sustained insert load keeps part counts bounded.

</specifics>

<deferred>
## Deferred Ideas

- **Receiver-side backpressure → OTLP status mapping** — the storage layer exposes the backpressure signal in Phase 2; translating it to `RESOURCE_EXHAUSTED`/`UNAVAILABLE` and OTLP `partial_success` belongs to the receiver (Phase 3).
- **Deep late/out-of-order/incomplete-trace replay-merge correctness** — schema stays merge-friendly now; the hard max-wins/never-arriving-span semantics are designed in Phase 4 alongside replay (PITFALLS flags this for deeper research).
- **Disk-spill / WAL write durability** — rejected for v0.0.1; revisit only if a real deployment needs survive-ClickHouse-restart durability beyond bounded retry.
- **`async_insert` server-side buffering** — client-side batching is the primary mechanism; server-side `async_insert=1` is a possible additional fallback, not a v0.0.1 requirement.

</deferred>

---

*Phase: 2-clickhouse-storage-layer*
*Context gathered: 2026-06-21*
