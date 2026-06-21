# Phase 2: ClickHouse Storage Layer - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-21
**Phase:** 2-clickhouse-storage-layer
**Areas discussed:** Null semantics, Payload storage, agent_runs rollup, Write-failure policy

> Mode: the user asked for Claude's recommendation on all four areas and then accepted all four recommendations ("do it"). Recommended option marked ✓.

---

## Null semantics

| Option | Description | Selected |
|--------|-------------|----------|
| Nullable() on metric cols only | Nullable on aggregate-feeding metrics (cost, tokens, saturation), kept out of ORDER BY; sentinel defaults elsewhere | ✓ |
| Sentinel defaults everywhere | No Nullable anywhere; loses null≠0 distinction | |
| Defaults + presence bitmask | Defaults plus a flags column marking presence | |

**User's choice:** Nullable() on metric cols only (D-01/D-02).
**Notes:** Preserves Phase-1 D-12 (null ≠ 0) exactly where the product premise needs it (cost/token/saturation aggregation) while respecting ClickHouse's "avoid Nullable" guidance on sort-key/high-cardinality columns.

---

## Payload storage

| Option | Description | Selected |
|--------|-------------|----------|
| Separate payloads table | Keyed by (TraceId, SpanId); keeps spans table lean for analytics; point-lookup for replay/detail | ✓ |
| Inline columns in spans | InputPayload/OutputPayload as String columns in spans; fat rows bloat analytical scans | |

**User's choice:** Separate payloads table (D-04/D-05).
**Notes:** Spans table is the hot analytical path; 256 KB blobs only ever fetched as point lookups for replay/span-detail (STOR-03 "retrieved individually"). Payloads share the spans TTL window.

---

## agent_runs rollup

| Option | Description | Selected |
|--------|-------------|----------|
| Build MV now (AggregatingMergeTree) | Incremental rollup MV created this phase; engine + ORDER BY pinned now (unrecoverable) | ✓ |
| Schema now, populate in Phase 4 | Define DDL now, defer wiring | |
| Compute at query time | No materialized rollup; GROUP BY over spans in Phase 4 | |

**User's choice:** Build MV now (AggregatingMergeTree), with test scope limited to complete/in-order traces + dedup (D-06/D-07).
**Notes:** An incremental MV self-populates on insert, so "build now" honestly satisfies STOR-01; the unrecoverable engine/ORDER BY decision is made now with research. Deep late/out-of-order replay-merge correctness deferred to Phase 4 (where the consumer and PITFALLS Pitfall 5/6 deep-research live). This was the one area Claude flagged for judgment; user accepted the recommendation.

---

## Write-failure policy

| Option | Description | Selected |
|--------|-------------|----------|
| Bounded retry, then backpressure | Retry flush w/ backoff; bounded Channel suspends producer on full; storage exposes backpressure signal | ✓ |
| Drop + metric on overflow | Drop spans + counter; never blocks but silently loses traces | |
| Retry + disk spill | Retry + local disk WAL/spool, replay on recovery | |

**User's choice:** Bounded retry, then backpressure (D-08).
**Notes:** OTLP-spec-correct (PITFALLS Pitfall 4). Dropping rejected — a debugging tool must not silently lose traces. Disk-spill/WAL rejected as over-built for v0.0.1 Compose. Receiver-side OTLP status mapping is Phase 3.

## Claude's Discretion

- DDL column naming/casing, CODEC choices, bloom-filter granularity, partition granularity.
- Migration runner mechanism (startup-applied + versioned is fixed; the how is open).
- Exact flush thresholds / retry-backoff counts and their env-configurability.
- Repository/DAO API shape, `storage/` package layout, coroutine bridging of client-v2 futures.
- Default TTL duration (14–30 day range; existence of a TTL clause is mandatory).

## Deferred Ideas

- Receiver-side backpressure → OTLP `RESOURCE_EXHAUSTED`/`UNAVAILABLE` + `partial_success` mapping (Phase 3).
- Deep late/out-of-order/incomplete-trace replay-merge correctness (Phase 4).
- Disk-spill / WAL write durability (post-v0.0.1).
- `async_insert` server-side buffering as an additional fallback (not v0.0.1).
