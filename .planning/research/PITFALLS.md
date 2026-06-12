# Pitfalls Research

**Domain:** OTLP trace receiver + ClickHouse storage + React/D3 DAG visualizer (agent-native tracing)
**Researched:** 2026-06-12
**Confidence:** HIGH (ClickHouse + OTLP pitfalls verified against official ClickHouse docs/blog and the OTLP spec; D3 and Ktor/gRPC items MEDIUM — community-verified)

## Critical Pitfalls

### Pitfall 1: Per-request INSERTs into ClickHouse → "Too many parts" death spiral

**What goes wrong:**
The OTLP receiver inserts each incoming export request (often 1–100 spans) directly into ClickHouse. Every INSERT creates a new on-disk part in MergeTree. Merges fall behind, queries slow down, and ClickHouse eventually rejects inserts with `TOO_MANY_PARTS`. This is the single most common failure mode in homegrown ClickHouse trace pipelines.

**Why it happens:**
The natural shape of an OTLP receiver is "request in → rows out," and it works perfectly in dev at 10 spans/minute. The failure only appears under sustained ingest. ClickHouse's own docs call this "almost always a workload issue, not a configuration issue."

**How to avoid:**
Put a batching buffer between the receiver and ClickHouse from day one — a Kotlin coroutine channel/Flow that accumulates spans and flushes on size (1,000–10,000+ rows) or time (1–5 s) thresholds. Alternatively (or additionally) enable ClickHouse `async_insert = 1` with `wait_for_async_insert = 1` so ClickHouse buffers server-side. Do NOT rely on per-export inserts even for v0.0.1 — retrofitting batching changes the receiver's error-handling and backpressure contract.

**Warning signs:**
- `system.parts` shows >300 active parts per partition
- INSERT latency climbing; `Too many parts` exceptions in logs
- Receiver code path that calls JDBC insert inside the gRPC/HTTP handler

**Phase to address:**
Backend ingestion phase (Phase 1). The batching layer is part of the receiver architecture, not an optimization.

---

### Pitfall 2: Wrong ClickHouse ORDER BY / partitioning — trace lookup by ID is slow, or compression collapses

**What goes wrong:**
Two opposite mistakes, both fatal:
1. `ORDER BY (trace_id, ...)` — trace_id is random/high-cardinality, so sorting by it destroys compression and makes every analytics query (top-cost runs, filters by agent/model) a full scan.
2. `ORDER BY (service, timestamp, ...)` only — analytics are fast, but the core UX operation "fetch all spans for trace X" becomes a full scan, making the DAG view unusably slow at scale.
Separately, partitioning by day or by a high-cardinality key creates thousands of tiny parts and degrades everything.

**Why it happens:**
A single table can't serve both access patterns ("analytics over time ranges" and "point lookup by trace_id"), and developers pick one ORDER BY hoping it covers both. Partitioning is misunderstood as a performance feature; it's a data-management feature.

**How to avoid:**
Use the documented two-table pattern from ClickHouse's own OTel trace schema:
- Main `spans` table: `PARTITION BY toDate(Timestamp)` (or coarser), `ORDER BY (ServiceName, SpanName, toUnixTimestamp(Timestamp), TraceId)` — order low→high cardinality, matching your filter columns (agent, model, outcome).
- A materialized view `trace_id_ts` mapping `TraceId → (min(Timestamp), max(Timestamp))`, ORDER BY TraceId. Trace lookup becomes: resolve time range from the MV, then range-scan the main table.
Add a bloom-filter skipping index on TraceId as a secondary aid. Avoid Nullable columns throughout (storage + query overhead per official guidance — use empty-string/zero defaults).

**Warning signs:**
- "Open trace" in the UI takes seconds while the spans table is still small
- Compression ratio of the spans table is poor (check `system.columns` compressed vs uncompressed)
- Partition key with > ~100 partitions in a month of dev data

**Phase to address:**
Backend storage phase (Phase 1) — schema design. Changing ORDER BY later requires a full table rewrite.

---

### Pitfall 3: Burying agent attributes (tokens_used, cost_usd, skill_name) in the generic attributes Map

**What goes wrong:**
The whole product premise — "token cost on every node," cost-range filters, top-10-expensive-runs queries — runs against attributes. If `tokens_used`/`cost_usd` live only inside a `Map(String, String)` attributes column, every query must deserialize the whole map for every row, and numeric aggregation requires casting strings. Filters and overlays get slower as data grows, exactly where this tool claims to beat Jaeger.

**Why it happens:**
The OTLP data model delivers attributes as a generic key-value list, and the path of least resistance is to store them as-is. OTLP attribute values are also typed (int/double/string), and naive extraction stringifies everything.

**How to avoid:**
agent-tracer owns the attribute convention — exploit that. Extract `skill_name`, `context_saturation_pct`, `tokens_used`, `cost_usd` (plus model, agent_id, outcome) into dedicated, properly typed materialized/first-class columns at ingest. Keep the raw Map column for everything else. Preserve OTLP value types in the extractor (an int attribute must land in a UInt64/Float64 column, not `"1234"`). This mirrors what ClickStack/SigNoz do for `service.name` etc.

**Warning signs:**
- SQL in the query layer containing `Attributes['cost_usd']` with casts
- Cost-range filter noticeably slower than date-range filter
- Aggregations returning wrong results because a string-typed "0.05" sorted lexicographically

**Phase to address:**
Backend storage + attribute-extractor phase (Phase 1). The convention doc and the schema must be designed together.

---

### Pitfall 4: OTLP receiver ignores protocol semantics — 4 MB gRPC limit, partial success, backpressure status codes

**What goes wrong:**
Three related failures:
1. **Message size:** gRPC default max inbound message size is 4 MB. Agent spans are unusually fat (prompt/completion text, tool args in attributes), so legitimate exports get rejected with `RESOURCE_EXHAUSTED`, and the sender silently drops or retry-loops the batch. This is the exact failure documented against elastic/apm-server and otel-collector.
2. **Partial success:** OTLP requires returning an `ExportTraceServiceResponse` with `partial_success` populated when some spans are rejected. Returning a bare error makes well-behaved SDKs retry the whole batch → duplicate spans.
3. **Backpressure:** when overloaded, the server must return gRPC `UNAVAILABLE` (optionally with `RetryInfo`) — not hang, not OOM, not return `OK` while dropping data. For OTLP/HTTP, the analogous 429/503 + Retry-After.

**Why it happens:**
A receiver that just deserializes protobuf and writes rows passes every happy-path test. Protocol edge semantics only matter when a real SDK with retry logic hits a real limit.

**How to avoid:**
- Raise gRPC max inbound message size explicitly (16–32 MB) and make it configurable; document it.
- Implement the OTLP response messages correctly, including `partial_success` with `rejected_spans` count and message.
- Tie backpressure to the ingest buffer: when the batch buffer is full, return `UNAVAILABLE`/`RESOURCE_EXHAUSTED` per spec rather than queueing unboundedly.
- Test with the real OpenTelemetry Java/Kotlin SDK exporter and with `telemetrygen`/otel-collector as senders, not only hand-rolled protobuf.
- Don't forget OTLP/HTTP requirements: gzip request encoding support and protobuf-encoded responses.

**Warning signs:**
- Receiver tests only use small hand-built payloads
- No code path that ever returns a non-OK gRPC status
- KORE-side logs showing export retries/drops while tracer logs show nothing

**Phase to address:**
Backend ingestion phase (Phase 1). Conformance is part of "OTLP receiver works," not polish.

---

### Pitfall 5: Assembling traces at ingest time — late, out-of-order, and orphaned spans break the DAG

**What goes wrong:**
The system builds the agent DAG (or the `agent_runs` rollup) when spans arrive, assuming the trace is complete. Real traces arrive out of order (children before parents — spans are exported when they *end*), late (batching delays, long-running subagents reporting minutes later), or incomplete (a crashed subagent never exports its span). Result: DAGs with missing roots, "finished" runs that later grow nodes, and replay sequences in the wrong order. Products that assemble at ingest with short expiry windows split long agent runs into multiple broken traces — and agent runs are exactly the long-lived, async workloads where this hurts most.

**Why it happens:**
Ingest-time assembly feels efficient and makes the `agent_runs` table easy to populate. The failure modes only appear with realistic emitters and long runs.

**How to avoid:**
- Store spans as immutable append-only facts; assemble the tree at **query time** from parent_span_id links.
- Make the DAG builder tolerant: render orphan spans under a synthetic "unknown parent" node rather than dropping them; mark traces with missing roots as "possibly incomplete" in the UI.
- If an `agent_runs` summary table is needed, populate it via scheduled/lazy aggregation or a refreshable materialized view keyed by trace_id with max-wins merge semantics (ReplacingMergeTree/AggregatingMergeTree) — never via UPDATE of an existing row.
- Order replay steps by span start time but display clock-skew defensively (a child starting "before" its parent should not crash layout).

**Warning signs:**
- Any code that "finalizes" a trace N seconds after last span
- DAG component throws or renders empty when parent_span_id has no matching span
- Replay mode assumes spans arrive sorted

**Phase to address:**
Backend query/assembly phase (Phase 1 for storage model; DAG assembly logic at the start of the frontend/API phase). Flag for deeper research when designing replay mode.

---

### Pitfall 6: ALTER UPDATE/DELETE (mutations) used for run status, dedup, or retention

**What goes wrong:**
Using mutations to update `agent_runs.outcome` when a run completes, deduplicate retried spans, or delete old traces. Mutations rewrite entire parts asynchronously, are extremely expensive, stack up under load, and can block merges — compounding the "too many parts" problem. This is the canonical "treating ClickHouse like Postgres" mistake.

**Why it happens:**
The team's mental model is relational (the ecosystem default is SQLite/Postgres). "The run finished, so UPDATE its row" is the obvious move.

**How to avoid:**
- Status changes: insert a new version row into a ReplacingMergeTree (version = updated_at) and read with `FINAL` or argMax — or compute status at query time from span data.
- Dedup of retried OTLP batches: rely on ReplacingMergeTree keyed on (trace_id, span_id) for eventual dedup; tolerate transient duplicates in queries (`GROUP BY span_id` / `LIMIT 1 BY`).
- Retention: `TTL toDateTime(Timestamp) + INTERVAL 30 DAY` on the table — never DELETE statements. Define TTL in the v0.0.1 schema; unbounded growth in a Docker Compose volume is a guaranteed user complaint.

**Warning signs:**
- Any `ALTER TABLE ... UPDATE/DELETE` in application code
- `system.mutations` showing queued mutations
- No TTL clause in CREATE TABLE statements

**Phase to address:**
Backend storage phase (Phase 1) — encode in schema and repository-layer conventions.

---

### Pitfall 7: D3 force-directed SVG layout for agent DAGs — unusable beyond a few hundred nodes

**What goes wrong:**
The default "D3 graph" tutorial path is a force simulation rendering one SVG element per node/edge/label. Agent traces with deep subagent hierarchies easily reach hundreds–thousands of spans; SVG DOM costs make pan/zoom janky around 1,000–2,000 elements, and force layout is both slow (O(n²)-ish per tick) and *wrong* for this domain — a decision tree needs deterministic hierarchical layout, not a physics wobble that renders differently every load.

**Why it happens:**
Force-directed is what D3 examples show, and small demo traces look fine. The cost appears with the first real production trace.

**How to avoid:**
- Use a layered/DAG layout algorithm (dagre, elkjs, or d3-dag/d3-hierarchy for trees) computed once, deterministically — D3 then handles only rendering/zoom/pan.
- Set a node-count budget: SVG is fine to ~1–2k visible elements; above that, collapse subtrees by default (collapsed subagent groups, expand on click) and/or switch the renderer to Canvas with a quadtree for hit-testing.
- Keep React out of the per-node render path (no React component per span node for large graphs); let D3/canvas own the graph surface inside one React-managed container.
- Test with a generated 5,000-span trace before declaring the DAG view done.

**Warning signs:**
- `d3-force` in package.json for the DAG view
- One React component instance per graph node
- Demo traces all under 100 spans

**Phase to address:**
Frontend/DAG phase (Phase 2+). Decide layout algorithm and rendering budget at phase planning, not mid-implementation. Flag for phase-specific research (dagre vs elkjs vs d3-dag).

---

### Pitfall 8: Netty classpath conflict between Ktor and grpc-java in one JVM

**What goes wrong:**
Ktor (Netty engine) and `io.grpc:grpc-netty` both pull Netty, at different versions. Result: `NoSuchMethodError`/`NoClassDefFoundError` at startup or — worse — only when the first gRPC call arrives. Also, mixed versions across `grpc-*` artifacts themselves cause the same class of failure.

**Why it happens:**
Gradle resolves a single Netty version for the whole classpath; whichever transitive version "wins" breaks the other library. It works on the dev machine until a dependency bump shifts resolution.

**How to avoid:**
- Use `io.grpc:grpc-netty-shaded` (Netty relocated into the grpc namespace) so Ktor's Netty and gRPC's Netty never share classes. This is grpc-java's documented recommendation for exactly this situation.
- Pin all `io.grpc:*` artifacts to one version via a Gradle platform/BOM (`io.grpc:grpc-bom`).
- Run the two servers on separate ports (e.g., 4317 gRPC, 4318 + UI API on Ktor); don't attempt to multiplex OTLP gRPC through Ktor — Ktor's gRPC story (kotlinx-rpc) is still maturing and not OTLP-compatible.
- Alternative worth considering at design time: use the upstream `opentelemetry-sdk`/`opentelemetry-exporter` server components or implement OTLP/HTTP-protobuf first (pure Ktor, no grpc-java at all) and add gRPC second.

**Warning signs:**
- `gradle dependencies` showing both `io.netty:netty-*` (from Ktor) and unshaded grpc-netty
- Startup works but first OTLP export crashes the channel
- Mixed grpc artifact versions in the lockfile

**Phase to address:**
Backend ingestion phase (Phase 1) — dependency architecture decision before writing receiver code.

---

### Pitfall 9: Giant prompt/completion payloads stored inline as span attributes

**What goes wrong:**
Replay mode needs the agent's context, so emitters stuff full prompts/completions into span attributes. Consequences cascade: OTLP messages blow the 4 MB limit (Pitfall 4), the attributes Map column bloats ClickHouse storage and slows every map read (Pitfall 3), the DAG API ships megabytes of text the graph view never displays, and sensitive prompt content (user data, secrets in tool args) lands unredacted in a long-retention store.

**Why it happens:**
"Just put it in an attribute" is the easiest contract for emitters, and small dev prompts hide the cost.

**How to avoid:**
- In the attribute convention agent-tracer publishes, define explicit large-payload attributes (e.g., `gen_ai.prompt`, `gen_ai.completion` per emerging OTel GenAI semconv) with documented size guidance, and store them in dedicated ZSTD-compressed String columns (or a separate `span_payloads` table keyed by span_id), not in the generic Map.
- Make the DAG/list APIs exclude payload columns; fetch payloads lazily only in replay/context viewer.
- Document a truncation limit at ingest (configurable, e.g., 256 KB/attribute) so one pathological span can't poison a batch.

**Warning signs:**
- `SELECT *` queries serving the DAG view
- Trace-list endpoint response sizes in MB
- ClickHouse disk usage dominated by the Attributes column (check `system.columns`)

**Phase to address:**
Phase 1 (schema + convention doc) and re-verified in the replay-mode phase.

---

### Pitfall 10: Docker Compose "all-in-one" that works on the author's machine only

**What goes wrong:**
The flagship distribution promise fails on first run for users: ClickHouse needs ~2 GB RAM just to initialize its allocator and dies on small Docker Desktop allocations; the tracer starts before ClickHouse is ready to accept connections and crashes; data vanishes on `docker compose down` because no named volume; ClickHouse wants high `nofile` ulimits; default user/password and exposed ports differ across ClickHouse image versions.

**Why it happens:**
Compose files are written once on a 32 GB dev machine and never tested cold on a constrained host.

**How to avoid:**
- ClickHouse service: named volume for `/var/lib/clickhouse`, `ulimits: nofile: 262144`, a low-memory dev config (`max_server_memory_usage_to_ram_ratio: 0.5`, reduced mark/uncompressed cache), explicit user/password via env vars.
- Tracer service: `depends_on: condition: service_healthy` with a real ClickHouse healthcheck (`clickhouse-client --query "SELECT 1"`), plus connection retry with backoff in the tracer itself (healthchecks race).
- Run schema migrations idempotently on tracer startup (`CREATE TABLE IF NOT EXISTS`), not as manual SQL steps.
- CI smoke test: `docker compose up`, wait healthy, send one OTLP trace with telemetrygen, query the API — on a memory-limited runner.

**Warning signs:**
- Compose file without healthcheck/volumes/ulimits
- README instructing manual `clickhouse-client` schema setup
- "Works locally" with no compose test in CI

**Phase to address:**
Packaging/distribution phase (final v0.0.1 phase), but bake the connection-retry and idempotent-migration behavior into the backend in Phase 1.

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Per-request ClickHouse inserts (skip batching) | Simpler receiver | Too-many-parts failures; batching retrofit changes backpressure contract | Never — async_insert is cheap enough for day one |
| All attributes in one Map column | No extractor needed | Slow filters/aggregations on the product's core metrics | Only for *non-agent* attributes; agent fields must be columns |
| Ingest-time trace finalization | Easy `agent_runs` table | Broken DAGs for long/async agent runs | Never for assembly; OK for *advisory* "last seen" metadata |
| HTTP/protobuf only, defer gRPC | Avoids grpc-netty entirely in v0.0.1 | KORE/SDKs default to gRPC 4317; adoption friction | Acceptable for an internal milestone, not for v0.0.1 (AC requires both) |
| SVG-only DAG renderer | Faster to build, easier styling | Rework when first >1k-span trace arrives | Acceptable if subtree-collapse keeps visible nodes <1k |
| No TTL on tables | One less schema decision | Unbounded disk growth in user deployments | Never — one line in DDL |
| Skipping OTLP partial_success responses | Less protobuf plumbing | Duplicate spans from SDK retries; spec non-conformance | Never |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| ClickHouse JDBC | One connection, synchronous inserts on request threads | Connection pool; batched async inserts via coroutine-based buffer; `RowBinary`/native format for bulk |
| ClickHouse (queries) | `SELECT FINAL` everywhere to hide duplicates | Query-time dedup (`LIMIT 1 BY span_id`) on hot paths; FINAL only where correctness demands |
| OTLP/gRPC senders | Assuming all senders behave like your test client | Conformance-test with otel-collector exporter + telemetrygen + OTel Java SDK; honor gzip, partial_success, status codes |
| OTLP/HTTP | Forgetting gzip request bodies and protobuf responses | Spec requires accepting gzip Content-Encoding; respond with protobuf-encoded ExportTraceServiceResponse |
| KORE (Tool 08) | Waiting on KORE to define attributes | agent-tracer publishes the convention first (already decided); validate with a fake emitter in tests |
| grpc-java + Ktor | Unshaded grpc-netty alongside Ktor-Netty | `grpc-netty-shaded` + grpc-bom version pinning; separate ports |
| React 19 + D3 | React and D3 both mutating the same DOM nodes | D3 owns the graph surface (ref'd container/canvas); React owns everything around it |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Small ClickHouse inserts | Rising part counts, insert errors | Batch ≥1k rows or async_insert | A few hundred export requests/min sustained |
| Trace lookup without trace_id MV | "Open trace" slow as table grows | trace_id→timestamp-range materialized view + bloom filter index | ~10M+ spans |
| Map attribute filters | Cost/skill filters degrade with volume | Materialized typed columns for agent fields | ~1M+ spans |
| SVG node-per-span DAG | Janky pan/zoom, frozen tab | Layered layout + collapse-by-default + canvas fallback | ~1–2k visible DOM elements |
| Force-directed layout | Multi-second layout, nondeterministic graphs | dagre/elkjs/d3-dag deterministic layout | Hundreds of nodes |
| Unbounded ingest queue as "backpressure" | Memory growth, OOM under burst | Bounded buffer + UNAVAILABLE/RESOURCE_EXHAUSTED per OTLP spec | First sustained burst above drain rate |
| Daily partitions + long retention | Merge pressure, slow startup | Partition by day only with TTL; or monthly partitions | Months of data |
| Shipping payload columns in list APIs | MB-sized JSON responses, slow UI | Column-projection in queries; lazy payload fetch for replay | First traces with real prompts |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| OTLP receiver open with no auth | Anyone on the network can inject fake traces or flood storage | Optional bearer-token/header auth on receiver, documented; bind to localhost by default in Compose |
| Raw prompts/tool args stored and displayed unredacted | Secrets/PII in user prompts persisted long-term and shown in replay UI | Document the risk in the attribute convention; configurable attribute redaction/truncation at ingest; TTL as mitigation |
| ClickHouse exposed with default/empty password in Compose | Direct DB access from host network | Generate/require password via env var; don't publish 9000/8123 to host by default, or document the exposure |
| Trusting span attributes in the UI (skill names, messages) | Stored XSS via attacker-controlled span attribute rendered in React/D3 SVG | Never use dangerouslySetInnerHTML/raw SVG text injection for attribute values; rely on React text escaping; sanitize anything injected into D3-built DOM |
| Unbounded message/attribute sizes | DoS via single giant export | Configurable max message size + per-attribute truncation limits |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Rendering the full DAG expanded for big traces | Unreadable hairball; defeats the "better than Jaeger" pitch | Collapse subagent subtrees by default; expand on demand; minimap for orientation |
| Empty dashboard on first run | User can't tell if ingestion works | Built-in demo trace seeder or `make demo` script; ingest-status indicator (spans/min received) |
| Hiding incomplete traces or crashing on orphans | Failed agent runs — the main debugging target — become invisible | Render partial traces with explicit "incomplete" badges; orphan spans under synthetic parent |
| Cost shown without context | "$0.0023" on a node means nothing | Show per-node cost AND cumulative subtree cost; trace-total in header |
| Replay ordered by arrival instead of span start time | Decision sequence appears scrambled | Order by start timestamp; tolerate clock skew visually |
| Filters that fire a ClickHouse query per keystroke | Sluggish UI, DB load | Debounced/applied-on-submit filters; server-side limits + pagination |

## "Looks Done But Isn't" Checklist

- [ ] **OTLP receiver:** Often missing partial_success responses, gzip support, and non-OK status codes — verify with otel-collector as a sender and a forced-overload test
- [ ] **gRPC ingest:** Often missing raised max message size — verify with a >4 MB synthetic export
- [ ] **ClickHouse schema:** Often missing TTL, trace_id lookup MV, and non-Nullable discipline — verify DDL review against ClickHouse OTel reference schema
- [ ] **Batching layer:** Often missing flush-on-shutdown (spans lost on container stop) — verify graceful shutdown drains the buffer
- [ ] **DAG view:** Often only tested with <100-span traces — verify with a generated 5,000-span deep-hierarchy trace
- [ ] **Trace assembly:** Often assumes complete, ordered spans — verify with shuffled ingestion and a deliberately missing parent span
- [ ] **Docker Compose:** Often missing healthcheck/volume/ulimits and never cold-tested — verify `docker compose up` from scratch on a 4 GB-RAM constrained environment in CI
- [ ] **Attribute convention:** Often published without type specs — verify doc states OTLP value type per attribute (int vs double vs string)
- [ ] **Filters API:** Often unindexed against schema ORDER BY — verify each filter column appears in ORDER BY, a materialized column, or a skipping index

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Too many parts in production | MEDIUM | Stop ingest, let merges catch up (`OPTIMIZE` cautiously), deploy batching/async_insert, resume |
| Wrong ORDER BY discovered late | HIGH | Create new table with correct keys, `INSERT INTO new SELECT FROM old`, swap names — full rewrite, plan downtime |
| Agent fields stuck in Map column | MEDIUM | Add MATERIALIZED columns (apply to new data), backfill via INSERT-SELECT into new table if historical queries matter |
| Netty conflict found at runtime | LOW | Swap to grpc-netty-shaded + grpc-bom; no code changes |
| SVG DAG too slow | MEDIUM | Add collapse-by-default first (cheap); canvas renderer second (rewrite of render layer only if layout was kept separate) |
| Unbounded disk growth at a user site | LOW | `ALTER TABLE ... MODIFY TTL` applies to existing data on next merge |
| Mutations queued/stuck | MEDIUM | `KILL MUTATION`, redesign write path to insert-only semantics |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Per-request inserts / too many parts | Phase 1 (ingestion) | Load test: sustained 100 exports/s for 10 min; `system.parts` stays bounded |
| Wrong ORDER BY / partitioning | Phase 1 (schema) | DDL review vs ClickHouse OTel reference; trace-open latency benchmark at 10M synthetic spans |
| Agent attrs in Map | Phase 1 (schema + extractor) | EXPLAIN on cost-filter query reads typed columns, not Map |
| OTLP protocol semantics | Phase 1 (ingestion) | Conformance tests: >4 MB message, gzip HTTP, overload → UNAVAILABLE, partial_success populated |
| Ingest-time trace assembly | Phase 1 (storage model) + DAG/API phase | Test: shuffled + late + orphaned span ingestion produces correct/partial-flagged DAG |
| Mutation abuse | Phase 1 (schema conventions) | Code review rule: no ALTER UPDATE/DELETE in app code; TTL present in all DDL |
| D3 layout/render scale | Frontend phase | 5,000-span generated trace renders <2 s, interactive pan/zoom |
| Netty/grpc classpath | Phase 1 (dependency setup) | `gradle dependencies` shows shaded grpc only; startup + first-export integration test |
| Giant payload attributes | Phase 1 (convention/schema), re-check in replay phase | List API response size budget test; payloads lazy-loaded in replay |
| Compose cold-start failures | Packaging phase | CI compose smoke test on memory-limited runner |

## Sources

- [ClickHouse: Storing Traces and Spans (OTel) — official trace schema, ORDER BY + trace_id MV pattern](https://clickhouse.com/blog/storing-traces-and-spans-open-telemetry-in-clickhouse) — HIGH
- [ClickHouse Docs: Designing a schema for observability — Nullable, ORDER BY, materialized columns](https://clickhouse.com/docs/use-cases/observability/schema-design) — HIGH
- [ClickHouse Docs: Too Many Parts lessons](https://clickhouse.com/docs/tips-and-tricks/too-many-parts) — HIGH
- [ClickHouse: Asynchronous inserts](https://clickhouse.com/blog/asynchronous-data-inserts-in-clickhouse) — HIGH
- [ClickHouse: 13 getting-started mistakes](https://clickhouse.com/blog/common-getting-started-issues-with-clickhouse) — HIGH
- [ClickHouse: How ClickStack makes ClickHouse faster for observability — materialized map attributes](https://clickhouse.com/blog/clickstack-faster-observability) — HIGH
- [OTLP Specification 1.10.0 — partial success, backpressure (UNAVAILABLE/RetryInfo), gzip](https://opentelemetry.io/docs/specs/otlp/) — HIGH
- [elastic/apm-server #14759 — OTLP/gRPC 4 MB message size limit in practice](https://github.com/elastic/apm-server/issues/14759) — MEDIUM
- [opentelemetry-collector #10025 — decompressed message exceeds 4 MB](https://github.com/open-telemetry/opentelemetry-collector/issues/10025) — MEDIUM
- [Observe: Why observability products struggle with tracing (ingest-time assembly, expiry windows)](https://www.observeinc.com/blog/why-many-observability-products-struggle-with-tracing) — MEDIUM (vendor blog, but failure mode is well-corroborated)
- [Honeycomb: spans out of order / missing timestamps](https://intercom.help/honeycomb/en/articles/2156263-spans-in-a-trace-appear-out-of-order-or-have-strange-time-offsets) — MEDIUM
- [d3/d3 #1936 — force layout performance with large node counts](https://github.com/d3/d3/issues/1936) — MEDIUM
- [AntStack: D3 network graphs, SVG→canvas + quadtree](https://www.antstack.com/blog/leveling-up-your-d3-network-graphs-from-simple-canvas-to-interactive-powerhouse/) — MEDIUM
- [grpc-spring troubleshooting — Netty version conflicts, grpc-netty-shaded recommendation](https://grpc-ecosystem.github.io/grpc-spring/en/trouble-shooting.html) — HIGH (grpc-ecosystem official)
- [opentelemetry-collector-contrib #24675 — slow queries against OTel attributes Map column](https://github.com/open-telemetry/opentelemetry-collector-contrib/issues/24675) — MEDIUM
- [ClickHouse/ClickHouse discussion #45532 — laptop dev Docker Compose setup](https://github.com/ClickHouse/ClickHouse/discussions/45532) — MEDIUM
- [OneUptime: ClickHouse single-node Docker Compose (memory ratio, healthchecks)](https://oneuptime.com/blog/post/2026-03-31-clickhouse-single-node-with-docker-compose/view) — LOW (single source; cross-checked memory requirement with ClickHouse docs)

---
*Pitfalls research for: OTLP/ClickHouse agent trace visualizer (agent-tracer, Tool 09)*
*Researched: 2026-06-12*
