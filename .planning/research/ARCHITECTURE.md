# Architecture Research

**Domain:** OTLP trace ingestion + ClickHouse storage + agent-native trace visualization
**Researched:** 2026-06-12
**Confidence:** HIGH (architecture/schema patterns verified against SigNoz official docs, ClickHouse engineering blog, Jaeger v2 design posts, OTel semconv spec) / MEDIUM (Kotlin gRPC specifics)

## Standard Architecture

Every successful ClickHouse-backed trace system (SigNoz, Jaeger v2 + ClickHouse, Uptrace, ClickStack) converges on the same four-layer shape: **receiver → write pipeline → columnar store → query service → SPA frontend**. SigNoz is the closest reference architecture: an OTel-compatible collector writes to ClickHouse, a query service (their "apiserver") exposes a JSON API, and a React frontend renders it. agent-tracer is a deliberately smaller, agent-native version of exactly this shape — with the collector and query service collapsed into one Ktor process.

### System Overview

```
┌────────────────────────────────────────────────────────────────────┐
│  EMITTERS (out of our control)                                     │
│  KORE (Tool 08) · any OTel SDK · OTel Collector forwarding         │
└───────────────┬───────────────────────────┬────────────────────────┘
                │ OTLP/gRPC :4317           │ OTLP/HTTP :4318
                ▼                           ▼  POST /v1/traces (protobuf)
┌────────────────────────────────────────────────────────────────────┐
│  TRACER BACKEND — single Kotlin/Ktor process                       │
│                                                                    │
│  ┌──────────────────┐   ┌──────────────────────────────────────┐   │
│  │ OTLP Receivers   │──▶│ Span Normalizer + Attribute          │   │
│  │ (gRPC + HTTP)    │   │ Extractor (gen_ai.* + unityinflow.*) │   │
│  └──────────────────┘   └──────────────┬───────────────────────┘   │
│                                        ▼                           │
│                         ┌──────────────────────────┐               │
│                         │ Batching Write Pipeline  │               │
│                         │ (Channel/Flow, flush by  │               │
│                         │  count + time)           │               │
│                         └──────────────┬───────────┘               │
│                                        │ batch INSERT               │
│  ┌──────────────────┐   ┌──────────────▼───────────┐               │
│  │ Query API (Ktor  │◀──│ ClickHouse Repository     │               │
│  │ REST/JSON)       │   │ (clickhouse client)       │               │
│  └────────┬─────────┘   └──────────────────────────┘               │
│           │  ┌─────────────────────────┐                           │
│           └──│ DAG Assembler (builds   │                           │
│              │ tree from parent_span_id│                           │
│              │ + agent run grouping)   │                           │
│              └─────────────────────────┘                           │
└───────────────┬────────────────────────────────────────────────────┘
                │ JSON over HTTP
                ▼
┌────────────────────────────────────────────────────────────────────┐
│  REACT 19 SPA (served as static assets by Ktor)                    │
│  Run list + filters │ D3 DAG view │ Saturation timeline │ Replay   │
└────────────────────────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────────┐
│  CLICKHOUSE (separate container, Docker Compose)                   │
│  spans (wide MergeTree table)                                      │
│  trace_id_ts MV (trace_id → time range)                            │
│  agent_runs MV (per-trace rollup: cost, tokens, outcome, agent)    │
└────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|------------------------|
| OTLP/gRPC receiver | Implement `opentelemetry.proto.collector.trace.v1.TraceService/Export` on :4317 | grpc-kotlin + Netty server embedded in the same JVM, using the published `io.opentelemetry.proto:opentelemetry-proto` bindings. (kotlinx-rpc now brings gRPC to Ktor, but it is schema-first/experimental — grpc-kotlin is the battle-tested path for a fixed external proto contract.) |
| OTLP/HTTP receiver | `POST /v1/traces` with `Content-Type: application/x-protobuf` (and optionally JSON) on :4318, per OTLP spec status codes | Plain Ktor route; parse `ExportTraceServiceRequest` with the same protobuf bindings. **Build this first** — it's ~50 lines and exercises the whole pipeline |
| Span normalizer / attribute extractor | Convert OTLP proto → internal immutable `AgentSpan` data class; promote `gen_ai.*` and `unityinflow.*` attributes to typed fields; keep the rest in an attributes map | Pure Kotlin, zero I/O — the easiest, highest-value unit-test surface in the project |
| Batching write pipeline | Decouple receive from insert; accumulate spans and flush by count (e.g., 5k) or interval (e.g., 2s), whichever first; backpressure via bounded Channel | Coroutine `Channel` + a single writer coroutine. ClickHouse is built for batch inserts, terrible at per-row inserts — both SigNoz and Jaeger v2 batch client-side |
| ClickHouse repository + migrations | Own schema DDL (applied at startup, versioned), batch inserts, all SELECT queries | `clickhouse-jdbc` (or the newer `client-v2`); Testcontainers for integration tests |
| ClickHouse schema | One wide `spans` table + materialized views for trace time-range lookup and agent-run rollups | See schema pattern below — this mirrors the otel-collector ClickHouse exporter and Jaeger v2 designs |
| Query API | REST/JSON for the frontend: list agent runs (filters), get full trace, saturation timeline series, replay step data | Ktor routes + kotlinx.serialization DTOs; thin layer over repository + DAG assembler |
| DAG assembler | Reconstruct decision tree from `parent_span_id` linkage; nest subagent runs; compute per-node cost rollups | Pure Kotlin on a list of spans for one trace — server-side, so the frontend receives a ready-to-render tree |
| React frontend | Run list w/ filters, D3 DAG with cost overlay, saturation timeline, replay stepper | React 19 + D3 (D3 for layout math, React owns the DOM), Zustand for UI state, TanStack Query for API data |
| Docker Compose | tracer + ClickHouse all-in-one; tracer serves SPA statics | Two services, healthcheck on ClickHouse, tracer `depends_on: condition: service_healthy` |

## Recommended Project Structure

Single Gradle module backend + sibling frontend folder (a multi-module split is overkill at this size; package boundaries enforce the layering):

```
agent-tracer/
├── backend/
│   ├── src/main/kotlin/io/github/unityinflow/agenttracer/
│   │   ├── domain/            # AgentSpan, AgentRun, DagNode — immutable data classes, sealed results
│   │   ├── convention/        # Attribute convention: keys, gen_ai.* mapping, extractor
│   │   ├── ingest/
│   │   │   ├── grpc/          # TraceService gRPC impl (grpc-kotlin)
│   │   │   ├── http/          # Ktor /v1/traces route
│   │   │   └── pipeline/      # Channel-based batcher, flush policy
│   │   ├── storage/           # ClickHouse client, migrations/, batch writer, query repository
│   │   ├── query/             # DAG assembler, run-list/timeline/replay services
│   │   ├── api/               # Ktor REST routes + DTOs
│   │   └── Application.kt     # wiring: starts Ktor + gRPC server + pipeline
│   ├── src/main/resources/migrations/   # 001_spans.sql, 002_mvs.sql ...
│   └── src/test/kotlin/...    # unit (domain/convention/query) + Testcontainers (storage)
├── frontend/                  # React 19 + Vite; built assets copied into backend statics
│   └── src/{api,store,components/{dag,timeline,replay,filters}}
├── docs/ATTRIBUTE-CONVENTION.md   # the published contract KORE will adopt
└── docker-compose.yml
```

### Structure Rationale

- **domain/ + convention/ first:** the attribute convention is this project's public contract — isolating it as pure code with no Ktor/ClickHouse imports makes the >80% coverage requirement cheap and lets KORE reference the doc independently.
- **ingest/ vs storage/ vs query/:** these are the three runtime seams. Ingest only produces `AgentSpan`s into a channel; storage only consumes batches and answers queries; query/ only reads via the repository. Nothing else touches ClickHouse SQL.
- **Server-side DAG assembly in query/:** the frontend gets a render-ready tree, keeping D3 code dumb and the build-order rule honest (the DAG logic is fully testable before any React exists).

## Architectural Patterns

### Pattern 1: Receiver → Channel → Batch Writer (decoupled ingest)

**What:** OTLP handlers never touch the database. They normalize spans and `send()` to a bounded coroutine Channel; one writer coroutine drains it and flushes batches to ClickHouse by size or time.
**When to use:** Always with ClickHouse — it is dramatically faster with batch inserts (SigNoz batches in its collector; Jaeger v2 benchmarked 10k–100k-span client-side batches).
**Trade-offs:** A crash loses the in-memory buffer (acceptable for v0.0.1 — SigNoz accepts the same window inside its collector). Bounded channel gives natural backpressure: when full, the gRPC handler can return OTLP `RESOURCE_EXHAUSTED`/retryable so SDKs retry.

```kotlin
val intake = Channel<AgentSpan>(capacity = 50_000)

// writer coroutine
launch {
    val batch = mutableListOf<AgentSpan>()
    whileSelect {
        intake.onReceive { batch += it; if (batch.size >= 5_000) flush(batch); true }
        onTimeout(2.seconds) { if (batch.isNotEmpty()) flush(batch); true }
    }
}
```

### Pattern 2: One wide spans table + materialized views (no relational normalization)

**What:** A single MergeTree `spans` table holds everything; derived access paths are ClickHouse materialized views, not joins. This is the consensus design — the otel-collector ClickHouse exporter, the ClickHouse team's own guidance, and Jaeger v2 (which explicitly benchmarked single-table vs multi-table and chose single-table) all do it.
**When to use:** Always for trace storage in ClickHouse.
**Schema sketch (verified against ClickHouse's official observability guide):**

```sql
CREATE TABLE spans (
  Timestamp        DateTime64(9) CODEC(Delta, ZSTD),
  TraceId          String        CODEC(ZSTD),
  SpanId           String,
  ParentSpanId     String,
  SpanName         LowCardinality(String),
  ServiceName      LowCardinality(String),
  Duration         UInt64,
  StatusCode       LowCardinality(String),
  -- promoted agent columns (typed, filterable — the whole point of this tool)
  AgentName        LowCardinality(String),
  Model            LowCardinality(String),
  SkillName        LowCardinality(String),
  InputTokens      UInt64,
  OutputTokens     UInt64,
  CostUsd          Float64,
  ContextSaturationPct Float32,
  -- everything else stays dynamic
  SpanAttributes   Map(LowCardinality(String), String),
  ResourceAttributes Map(LowCardinality(String), String),
  INDEX idx_trace_id TraceId TYPE bloom_filter(0.001) GRANULARITY 1
) ENGINE = MergeTree
PARTITION BY toDate(Timestamp)
ORDER BY (ServiceName, SpanName, toUnixTimestamp(Timestamp), TraceId)
TTL toDateTime(Timestamp) + INTERVAL 14 DAY SETTINGS ttl_only_drop_parts = 1;
```

Materialized views on top:
- **`trace_id_ts`** — `TraceId → (min(Timestamp), max(Timestamp))`, `ORDER BY (TraceId, ...)`. Standard two-step trace fetch: look up time range, then query `spans` with `TraceId = ? AND Timestamp BETWEEN ? AND ?` so the primary key prunes granules. The bloom filter on TraceId covers the same need; ship both, they're cheap.
- **`agent_runs`** — per-trace rollup keyed by root span: `(TraceId, AgentName, Model, Start, DurationMs, sum(InputTokens+OutputTokens), sum(CostUsd), max(ContextSaturationPct), Outcome, SpanCount)`, `ORDER BY (toUnixTimestamp(Start), TraceId)` partitioned daily. **This MV is the run-list/filter screen** — agent, model, cost range, outcome, date range all become direct columns; no scan of `spans` needed for the landing page. Use `AggregatingMergeTree`/`SummingMergeTree` semantics or finalize with `GROUP BY` at query time.

**Trade-offs:** ORDER BY can't serve every filter — that's what the rollup MV and bloom filters are for. Promoted columns mean the extractor must be versioned with the schema (one more reason agent-tracer owns the convention).

### Pattern 3: Server-side DAG assembly from `parent_span_id`

**What:** OTLP already encodes the DAG: every span carries `trace_id`, `span_id`, `parent_span_id`. The query service fetches all spans of a trace (one query via the trace_id path), builds the tree in memory (`groupBy(parentSpanId)`), classifies nodes via `gen_ai.operation.name`/skill attributes (plan step, LLM call, tool call, subagent invoke), computes rollup cost per subtree, and returns nested JSON.
**When to use:** Always — agent traces are bounded (hundreds to low thousands of spans), so in-memory assembly per request is trivial; pushing raw spans to the browser and assembling in JS wastes bandwidth and duplicates logic outside test coverage.
**Subagent hierarchy:** falls out for free — a subagent spawn is a span whose subtree is itself an agent run (`gen_ai.operation.name = invoke_agent`). Nested DAG = nodes flagged `isAgentRoot` that the frontend can collapse/expand. Cross-trace subagents (separate trace_id linked via span links) can be deferred; same-trace parent/child covers KORE.
**Replay mode is a view over the same payload:** spans sorted by start time = the step sequence; each step's "context viewer" renders that span's attributes/events. No extra storage or API shape needed beyond ordering and span events.

### Pattern 4: Define the attribute convention as gen_ai.* superset, not a parallel universe

**What:** OTel's GenAI semantic conventions (status: **Development**, so pin the version you target) already standardize what this project needs for tokens and agent identity: `gen_ai.operation.name` (`invoke_agent`, `execute_tool`, `chat`), `gen_ai.agent.name`/`gen_ai.agent.id`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`/`gen_ai.usage.output_tokens` (+ cache variants), `gen_ai.tool.name`, `gen_ai.conversation.id`. They define **no cost attribute and no context-saturation attribute** — those are exactly the gaps agent-tracer's convention fills.
**The published convention should therefore be:**

| agent-tracer concept | Attribute to read (priority order) | Notes |
|---|---|---|
| tokens_used | `gen_ai.usage.input_tokens` + `gen_ai.usage.output_tokens`; fallback `unityinflow.tokens_used` | Prefer the standard; store input/output separately |
| model | `gen_ai.request.model` / `gen_ai.response.model` | Standard |
| agent name | `gen_ai.agent.name` | Standard |
| skill_name | `unityinflow.skill.name`; also read `gen_ai.tool.name` for tool nodes | "Skill" is an ecosystem concept; namespace it (OTel reserves bare names for the spec) |
| cost_usd | `unityinflow.cost.usd` | No OTel standard exists — this is the convention's headline addition |
| context_saturation_pct | `unityinflow.context.saturation_pct` | No OTel standard exists |
| node kind | `gen_ai.operation.name` | Drives DAG node classification |

**Why:** anything emitting standard gen_ai.* (LangChain/OpenAI/Anthropic instrumentations, vendor SDKs) renders usefully in agent-tracer with zero changes, while KORE adds the `unityinflow.*` extras. The extractor is a small priority-ordered lookup — cheap to test exhaustively.
**Trade-offs:** gen_ai semconv is still in Development and has churned attribute names before (e.g., `gen_ai.system` → `gen_ai.provider.name`); isolate every key string in `convention/` so a rename is a one-file change.

## Data Flow

### Ingest Flow (write path)

```
OTel SDK / KORE
    ↓ OTLP protobuf (gRPC :4317 or HTTP :4318 /v1/traces)
Receiver → Normalizer/Extractor → Channel → Batch Writer
                                                  ↓ INSERT (5k rows / 2s)
                                          ClickHouse spans table
                                                  ↓ (automatic, on insert)
                                  trace_id_ts MV + agent_runs MV
```

### Query Flow (read path)

```
React SPA
    ↓ GET /api/runs?agent=&model=&minCost=&outcome=&from=&to=
Query API → agent_runs MV (no spans scan)              → run list JSON
    ↓ GET /api/traces/{traceId}
Query API → trace_id_ts (time range) → spans (TraceId+range)
          → DAG Assembler → nested DagNode tree JSON   → D3 render
    ↓ GET /api/traces/{traceId}/timeline
Query API → spans (Timestamp, ContextSaturationPct, tokens) → series JSON
```

### Key Data Flows

1. **Span ingest:** push-based, fire-and-forget from the emitter's perspective; durability boundary is the ClickHouse insert.
2. **Run discovery:** always served from the `agent_runs` rollup — the spans table is only touched once a specific trace is opened.
3. **Replay:** same trace payload as the DAG, consumed in start-time order; no separate storage.

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| 1k spans/day (launch target) | Everything above on one box; ClickHouse barely notices. Don't add anything. |
| 100k–10M spans/day | Tune batch size upward; verify MV rollups; this design (single node, daily partitions, TTL) is exactly what ClickHouse handles comfortably — Jaeger v2 reports 8.6x compression and fast queries on 10M spans with the same shape |
| Beyond | Put an OTel Collector in front for fan-in/retry buffering; ClickHouse replication; not a v0 concern |

### Scaling Priorities

1. **First bottleneck: insert pattern, not volume.** Per-span inserts will fall over orders of magnitude before batched inserts do. The batcher is day-one architecture, not an optimization.
2. **Second bottleneck: run-list queries scanning spans.** Solved structurally by the `agent_runs` MV; keep every filter the UI offers answerable from that MV.

## Anti-Patterns

### Anti-Pattern 1: Per-span INSERTs into ClickHouse

**What people do:** insert each span as it arrives, like with Postgres.
**Why it's wrong:** every insert creates a MergeTree part; ClickHouse throttles ("too many parts") and ingest collapses.
**Do this instead:** Channel + batch writer (Pattern 1); flush by count/time.

### Anti-Pattern 2: Normalizing traces into relational tables (spans, attributes, events tables + JOINs)

**What people do:** third-normal-form instinct — attribute key/value table joined to spans.
**Why it's wrong:** ClickHouse JOIN-heavy point lookups negate columnar advantages; Jaeger v2 benchmarked multi-table vs single-table and single-table won on search performance.
**Do this instead:** one wide table, `Map` columns for dynamic attributes, promoted typed columns for hot agent fields, MVs for derived access paths.

### Anti-Pattern 3: Rebuilding the OTel Collector

**What people do:** generic receiver/processor/exporter plugin pipeline "for flexibility."
**Why it's wrong:** months of work that the Collector already does; agent-tracer's value is agent semantics, not transport plumbing. Users with exotic sources can chain a real Collector in front (OTLP-out → agent-tracer-in).
**Do this instead:** two fixed OTLP endpoints, one fixed extractor, one fixed store.

### Anti-Pattern 4: Shipping raw spans to the browser and assembling the DAG in D3 code

**What people do:** `GET /spans?traceId=` returning flat OTLP-ish JSON; tree built in React.
**Why it's wrong:** duplicates domain logic in untested JS, bloats payloads, and breaks the "backend fully tested before frontend" rule — DAG correctness becomes untestable until Phase 2.
**Do this instead:** server-side DAG assembler returning nested, classified, cost-rolled-up nodes; D3 only does layout.

### Anti-Pattern 5: Letting D3 own the DOM inside React 19

**What people do:** `d3.select(...).append(...)` inside effects fighting React's reconciliation.
**Why it's wrong:** double DOM ownership causes ghost nodes and breaks React 19 concurrent rendering.
**Do this instead:** D3 for math only (`d3-hierarchy`/`d3-dag` layout, scales, zoom behavior); React renders the SVG elements from computed coordinates.

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| KORE (Tool 08) | Emits OTLP with `gen_ai.*` + `unityinflow.*` attributes per published convention | Convention doc is the contract; KORE instrumentation is KORE's work (per PROJECT.md) |
| Any OTel SDK / Collector | Standard OTLP export to :4317/:4318 | This is why receivers must be spec-compliant (status codes, partial success response) |
| ClickHouse | clickhouse-jdbc / client-v2, batch inserts, startup migrations | Own the schema explicitly (versioned SQL); never auto-create from the client per-process |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| ingest ↔ storage | `Channel<AgentSpan>` (in-process) | The only path spans take to the DB; receivers have no DB access |
| query ↔ storage | Repository interface (suspend funs returning domain types) | Query layer never sees SQL; enables fake-repo unit tests for DAG assembler |
| backend ↔ frontend | REST/JSON, versioned under `/api/v1` | Frontend is a pure API consumer; Ktor serves built assets |
| convention ↔ everything | `convention/` package + `docs/ATTRIBUTE-CONVENTION.md` | Single source of truth for attribute keys; the only place gen_ai churn is absorbed |

## Suggested Build Order (dependency-driven)

The ecosystem rule (backend fully built/tested before frontend) plus internal dependencies imply:

1. **Domain model + attribute convention + extractor** — pure Kotlin, no infra; publishes the contract KORE needs; highest test ROI. Depends on nothing.
2. **ClickHouse schema, migrations, batch writer, repository** — Testcontainers integration tests. Depends on (1) for the domain types.
3. **Ingest: OTLP/HTTP first, then OTLP/gRPC** — HTTP is a single Ktor route over the same protobuf parser (fast end-to-end proof); gRPC adds the embedded grpc-kotlin server. Depends on (1)+(2). Verify with a real OTel SDK exporter as the test client.
4. **Query API + DAG assembler + timeline/replay endpoints + filters** — depends on (2) for reads; completes the backend. Backend milestone: Docker Compose (tracer + ClickHouse) ingesting from an OTel SDK and answering all API queries — fully testable with curl before any React exists.
5. **Frontend: scaffold → run list/filters → DAG view w/ cost overlay → saturation timeline → replay mode** — strictly after (4); ordered so each screen consumes an already-proven endpoint, DAG (hardest UI) gets the most room.
6. **Packaging polish:** SPA served from Ktor, Compose all-in-one healthchecks, docs.

## Sources

- [SigNoz Technical Architecture (official docs)](https://signoz.io/docs/architecture/) — HIGH
- [SigNoz traces schema / ClickHouse query guide](https://signoz.io/docs/userguide/writing-clickhouse-traces-query/) — HIGH
- [ClickHouse blog: Building an Observability Solution — Part 2, Traces](https://clickhouse.com/blog/storing-traces-and-spans-open-telemetry-in-clickhouse) — HIGH (schema, ORDER BY, MV, bloom filter patterns)
- [otel-collector-contrib ClickHouse exporter README](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/exporter/clickhouseexporter/README.md) — HIGH (otel_traces schema, trace_id_ts MV, own-your-schema guidance)
- [Jaeger: design decisions for ClickHouse as core storage](https://medium.com/jaegertracing/making-design-decisions-for-clickhouse-as-a-core-storage-backend-in-jaeger-62bf90a979d) — HIGH (single-table choice, batching, MVs)
- [Jaeger native ClickHouse support announcement (v2.18, May 2026)](https://medium.com/jaegertracing/introducing-native-clickhouse-support-in-jaeger-3341e8208720) · [The New Stack: 8.6x compression on 10M spans](https://thenewstack.io/jaeger-clickhouse-storage-backend/) — MEDIUM/HIGH
- [OTel semconv: GenAI agent & framework spans](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/) · [GenAI spans](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/) — HIGH (attribute names, Development status, no cost/saturation attrs)
- [SigNoz on ClickHouse blog (architecture overview)](https://clickhouse.com/blog/signoz-observability-solution-with-clickhouse-and-open-telemetry) — HIGH
- [kotlinx-rpc gRPC in Ktor](https://github.com/Kotlin/kotlinx-rpc) · [Ktor Kotlin RPC tutorial](https://ktor.io/docs/tutorial-first-steps-with-kotlin-rpc.html) — MEDIUM (gRPC-in-Ktor maturity; grpc-kotlin recommended for the fixed OTLP proto contract)

---
*Architecture research for: OTLP + ClickHouse agent trace visualizer (agent-tracer, Tool 09)*
*Researched: 2026-06-12*
