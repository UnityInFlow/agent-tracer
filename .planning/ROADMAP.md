# Roadmap: agent-tracer

## Overview

agent-tracer is built convention-first and backend-first. Phase 1 publishes the agent span attribute convention — the project's public contract — with a pure-Kotlin extractor that understands both standard `gen_ai.*` semconv and the `unityinflow.*` extensions. Phase 2 lays the ClickHouse schema and batching write pipeline, where every "fix later = table rewrite" decision is made. Phase 3 opens the doors: spec-conformant OTLP receivers on the standard ports. Phase 4 completes the backend milestone — query API with server-side DAG assembly, a demo emitter, and the Docker Compose all-in-one proving cold-start ingest end-to-end with curl, before any React exists. Phase 5 ships the visualizer: interactive DAG with cost overlays, saturation timeline, nested subagent hierarchy, replay mode, and filters.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Attribute Convention & Domain Model** - Publish the agent span attribute contract and build the gen_ai/unityinflow extractor
- [ ] **Phase 2: ClickHouse Storage Layer** - Schema with typed agent columns, materialized views, payload storage, and the batching write pipeline
- [ ] **Phase 3: OTLP Ingestion** - Spec-conformant OTLP/HTTP and OTLP/gRPC receivers feeding the single batching pipeline
- [ ] **Phase 4: Query API, Demo Emitter & Compose Stack** - Backend milestone: DAG/filter/timeline API, synthetic trace generator, and `docker compose up` end-to-end
- [ ] **Phase 5: Frontend Visualization** - Interactive DAG with cost overlay, saturation timeline, nested subagents, replay mode, and run filters

## Phase Details

### Phase 1: Attribute Convention & Domain Model
**Goal**: Any OTel-emitting agent source produces spans agent-tracer understands — the published attribute convention is the ecosystem's public contract, and the extractor turns raw OTLP attributes into typed agent semantics
**Depends on**: Nothing (first phase)
**Requirements**: CONV-01, CONV-02, CONV-03
**Success Criteria** (what must be TRUE):
  1. A developer can read a published, versioned convention document defining `unityinflow.skill.name`, `unityinflow.context.saturation_pct`, `unityinflow.cost.usd` alongside the standard `gen_ai.*` token/model/agent attributes
  2. The convention document specifies the OTLP value type for every attribute and the handling rules for large prompt/completion payloads
  3. A span carrying only standard `gen_ai.*` attributes (`gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `invoke_agent`/`execute_tool` operations) is extracted into typed agent fields with no emitter changes
  4. A span carrying `unityinflow.*` extension attributes is extracted into typed agent fields (skill, saturation, cost), with extraction precedence over generic fallbacks verified by tests
**Plans**: TBD

### Phase 2: ClickHouse Storage Layer
**Goal**: Agent spans persist in a ClickHouse schema that serves trace lookup, run-list filtering, and replay at time-series scale — with every unrecoverable schema decision (ORDER BY, typed columns, payload storage, TTL) made correctly up front
**Depends on**: Phase 1
**Requirements**: STOR-01, STOR-02, STOR-03
**Success Criteria** (what must be TRUE):
  1. Spans written through the storage layer land in ClickHouse with typed first-class columns for agent fields (tokens, cost, saturation, skill, model) directly queryable in SQL — not buried in a generic attribute map
  2. Looking up a trace by trace_id is served via the materialized view path, verified to avoid a full table scan
  3. A span's input/output payloads can be stored and retrieved individually, sufficient to power replay and a span detail panel
  4. Writes flow through a batching pipeline (flush by count/time) — sustained insert load keeps ClickHouse part counts bounded, proven against a real ClickHouse instance via Testcontainers
**Plans**: TBD

### Phase 3: OTLP Ingestion
**Goal**: Any OTel-compatible source — KORE, a stock OTel SDK, or telemetrygen — can export traces to agent-tracer on the standard OTLP ports and have them land in ClickHouse
**Depends on**: Phase 2
**Requirements**: INGEST-01, INGEST-02, INGEST-03
**Success Criteria** (what must be TRUE):
  1. A real OTel SDK exporter posts traces to `POST /v1/traces` on :4318 (HTTP/protobuf) and the spans appear in ClickHouse
  2. A real OTel SDK exporter sends traces over gRPC to :4317, including prompt-heavy spans larger than the 4 MB gRPC default, and the spans appear in ClickHouse
  3. Both receivers return spec-conformant responses: `partial_success` on partial rejection, correct error/backpressure status codes when the pipeline is saturated
  4. Spans from both transports flow through the single Phase 2 batching pipeline — there is no per-request insert path
**Plans**: TBD

### Phase 4: Query API, Demo Emitter & Compose Stack
**Goal**: The complete backend milestone — a developer can `docker compose up`, generate realistic agent traces with the demo emitter, and answer every agent-debugging question with curl before any frontend exists
**Depends on**: Phase 3
**Requirements**: API-01, API-02, API-03, API-04, TOOL-01, DIST-01
**Success Criteria** (what must be TRUE):
  1. The API returns any agent run as an assembled DAG (parent-child span tree built at query time), tolerant of late, out-of-order, and orphaned spans
  2. The API returns the subagent hierarchy within a run as nested parent-child agent relationships with subtree cost roll-ups
  3. The API returns a filtered run list by agent, model, cost range, outcome, and date range, plus the context-saturation timeline series for any run
  4. The demo emitter generates realistic synthetic agent-run traces via OTLP that exercise the full attribute convention (skills, subagents, costs, saturation)
  5. `docker compose up` on a cold start brings up tracer + ClickHouse, ingests demo traces end-to-end, and serves API queries
**Plans**: TBD

### Phase 5: Frontend Visualization
**Goal**: A developer debugging an agent failure sees the full decision tree of any run — with token cost and context saturation on every node — instead of reading thousands of generic spans
**Depends on**: Phase 4
**Requirements**: VIZ-01, VIZ-02, VIZ-03, VIZ-04, VIZ-05, VIZ-06, VIZ-07
**Success Criteria** (what must be TRUE):
  1. User can open any agent run and view its full decision tree as an interactive DAG with token cost overlaid on every node
  2. Subagent hierarchies render as collapsible nested compound nodes within the DAG
  3. User can view the context window saturation timeline for a run
  4. User can step through agent decisions in replay mode with a context viewer showing input/output payloads
  5. User can filter the run list by agent, model, cost range, outcome, and date range, and click any DAG node to open a span detail panel with full attributes and payloads
**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Attribute Convention & Domain Model | 0/TBD | Not started | - |
| 2. ClickHouse Storage Layer | 0/TBD | Not started | - |
| 3. OTLP Ingestion | 0/TBD | Not started | - |
| 4. Query API, Demo Emitter & Compose Stack | 0/TBD | Not started | - |
| 5. Frontend Visualization | 0/TBD | Not started | - |
