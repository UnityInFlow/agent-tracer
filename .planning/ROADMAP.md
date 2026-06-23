# Roadmap: agent-tracer

## Overview

agent-tracer is built convention-first and backend-first. Phase 1 publishes the agent span attribute convention — the project's public contract — with a pure-Kotlin extractor that understands both standard `gen_ai.*` semconv and the `unityinflow.*` extensions. Phase 2 lays the ClickHouse schema and batching write pipeline, where every "fix later = table rewrite" decision is made. Phase 3 opens the doors: spec-conformant OTLP receivers on the standard ports. Phase 4 completes the backend milestone — query API with server-side DAG assembly, a demo emitter, and the Docker Compose all-in-one proving cold-start ingest end-to-end with curl, before any React exists. Phase 5 ships the visualizer: interactive DAG with cost overlays, saturation timeline, nested subagent hierarchy, replay mode, and filters.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Attribute Convention & Domain Model** - Publish the agent span attribute contract and build the gen_ai/unityinflow extractor (1 UAT gap → gap-closure plan 01-04 pending) (completed 2026-06-14)
- [x] **Phase 2: ClickHouse Storage Layer** - Schema with typed agent columns, materialized views, payload storage, and the batching write pipeline (UAT blocker closed via gap-closure plan 02-05; storage ITs observed green on real ClickHouse) (completed 2026-06-22)
- [ ] **Phase 3: OTLP Ingestion** - Spec-conformant OTLP/HTTP and OTLP/gRPC receivers feeding the single batching pipeline
- [ ] **Phase 4: Query API, Demo Emitter & Compose Stack** - Backend milestone: DAG/filter/timeline API, synthetic trace generator, and `docker compose up` end-to-end
- [ ] **Phase 5: Frontend Visualization** - Interactive DAG with cost overlay, saturation timeline, nested subagents, replay mode, and filters

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

**Plans**: 4 plans (3 complete + 1 gap-closure)

- [x] 01-01-PLAN.md — Gradle multi-module build scaffolding + agent-semconv constants module (vendored gen_ai.*/unityinflow.* keys + GenAiOperation enum)
- [x] 01-02-PLAN.md — Domain model (AgentSpan/AgentOutcome) + AttrView coercion + AgentSpanExtractor with precedence/clamp/nullability tests
- [x] 01-03-PLAN.md — Published versioned convention document (docs/agent-span-convention.md) + README link
- [x] 01-04-extend-agentspan-gen-ai-keys-PLAN.md — GAP CLOSURE (WR-01 / 01-UAT.md): add the four Recommended gen_ai.* fields (provider, agentId, toolCallId, conversationId) to AgentSpan and read them in AgentSpanExtractor

### Phase 2: ClickHouse Storage Layer

**Goal**: Agent spans persist in a ClickHouse schema that serves trace lookup, run-list filtering, and replay at time-series scale — with every unrecoverable schema decision (ORDER BY, typed columns, payload storage, TTL) made correctly up front
**Depends on**: Phase 1
**Requirements**: STOR-01, STOR-02, STOR-03
**Success Criteria** (what must be TRUE):

  1. Spans written through the storage layer land in ClickHouse with typed first-class columns for agent fields (tokens, cost, saturation, skill, model) directly queryable in SQL — not buried in a generic attribute map
  2. Looking up a trace by trace_id is served via the materialized view path, verified to avoid a full table scan
  3. A span's input/output payloads can be stored and retrieved individually, sufficient to power replay and a span detail panel
  4. Writes flow through a batching pipeline (flush by count/time) — sustained insert load keeps ClickHouse part counts bounded, proven against a real ClickHouse instance via Testcontainers

**Plans**: 5 plans in 5 waves (1 gap-closure)
**Wave 1**

- [x] 02-01-PLAN.md — Wave 0: storage + Testcontainers dependencies (client-v2, coroutines, testcontainers, kotlin-logging, logback) + shared ClickHouse 26.3 container fixture + deterministic SpanFixtures

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 02-02-PLAN.md — Four idempotent DDL migrations (spans / payloads / trace_id_ts MV / agent_runs AggregatingMergeTree MV) + env-var config + client-v2 factory + startup MigrationRunner; ClickHouseSchemaIT proves apply/idempotency/EXPLAIN pruning (STOR-01, STOR-02)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 02-03-PLAN.md — Row POJOs + SpanRepository seam + ClickHouseSpanRepository (RowBinary insert, two-step trace-by-id, agent_runs listRuns, payload point-lookup, all parameterized); SpanRepositoryIT proves round-trips + rollup + payloads (STOR-01, STOR-02, STOR-03)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 02-04-PLAN.md — AgentSpanRowMapper (256 KB cap + truncation) + SpanWriter bounded-Channel batching pipeline with retry-then-backpressure + BackpressureSignal; SpanWriterTest + PartCountIT prove flush/backpressure/no-drop + bounded parts under load (STOR-01, STOR-03, SC4)

**Wave 5** *(gap closure — UAT blocker)*

- [x] 02-05-PLAN.md — Gap closure: comment-safe SQL statement splitting in MigrationRunner (strip `--` comments before `;` split — a semicolon in a header comment was leaking garbage into CREATE TABLE → Code 62 SYNTAX_ERROR, schema never applied) + Docker-free regression test + observed all three storage ITs GREEN against real ClickHouse on Docker Desktop. Also fixed Code 549 (SimpleAggregateFunction columns illegal in key/TTL → plain DateTime key columns), a JUnit silently-skipped-expression-body bug masking verification, and re-anchored PartCountIT TTL fixtures; D-07 dedup deferred to Phase 4 (Option A). Full backend suite 65/65 green, 0 skipped (STOR-01/02/03)

### Phase 3: OTLP Ingestion

**Goal**: Any OTel-compatible source — KORE, a stock OTel SDK, or telemetrygen — can export traces to agent-tracer on the standard OTLP ports and have them land in ClickHouse
**Depends on**: Phase 2
**Requirements**: INGEST-01, INGEST-02, INGEST-03
**Success Criteria** (what must be TRUE):

  1. A real OTel SDK exporter posts traces to `POST /v1/traces` on :4318 (HTTP/protobuf) and the spans appear in ClickHouse
  2. A real OTel SDK exporter sends traces over gRPC to :4317, including prompt-heavy spans larger than the 4 MB gRPC default, and the spans appear in ClickHouse
  3. Both receivers return spec-conformant responses: `partial_success` on partial rejection, correct error/backpressure status codes when the pipeline is saturated
  4. Spans from both transports flow through the single Phase 2 batching pipeline — there is no per-request insert path

**Plans**: 4 plans in 3 waves

**Wave 1**

- [x] 03-01-PLAN.md — Phase-3 deps (Ktor/gRPC/otel-sdk) + the transport-agnostic core: OtlpDecoder (D-06) + DecodeResult (D-02) + OtlpResponses (partial_success builder) + ReceiverConfig (env ports/limits); OtlpDecoderTest pins the D-07 hex-id format byte-for-byte with a round-trip assertion (risk-reducer, Pitfall 1) (INGEST-01/02/03)

**Wave 2** *(blocked on Wave 1; the two transports own disjoint files → run in parallel)*

- [x] 03-02-PLAN.md — OTLP/HTTP receiver: Ktor `Route.otlpTraces` on :4318 (200 full/partial, 400 undecodable, 429+Retry-After fail-fast, 413 body-size guard) + OtlpHttpRoutesTest via ktor-server-test-host, 5 tests 0 skipped, fail-fast proven behaviorally (INGEST-01, INGEST-03)
- [x] 03-03-PLAN.md — OTLP/gRPC receiver: OtlpGrpcService extends TraceServiceImplBase on :4317 with the StreamObserver→suspend coroutine bridge + RESOURCE_EXHAUSTED saturation + OtlpGrpcServiceTest over an in-process channel (INGEST-02, INGEST-03)

**Wave 3** *(blocked on Wave 2)*

- [x] 03-04-PLAN.md — App bootstrap: TracerApplication fun main() (migrate→serve→drain, both servers, D-04/D-05) + real-exporter Testcontainers ITs: OtlpHttpReceiverIT (SC1, closes the D-07 round-trip + D-03 generic store-all span), OtlpGrpcReceiverIT (SC2, 6 MB span truncate-not-reject), TracerApplicationIT (boot ordering + drain-no-loss). Compiles + lints clean + SC4 grep gate clean; ITs implemented with real OtlpHttp/GrpcSpanExporter — operation-level proof deferred to native Docker/CI (sandbox `ContainerLaunchException: testcontainers/ryuk:0.12.0`, the documented Phase-2/kore infra limit, not code) (INGEST-01/02/03)

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

**Plans**: 5 plans in 4 waves

**Wave 1**

- [x] 04-01-PLAN.md — Foundation: net-new JSON stack (kotlinx-serialization-json + ktor content-negotiation/serialization) + `application` packaging plugin; `listRuns` newest-first + offset/limit (D-05); Wave-0 RED test stubs (DagAssembler/Saturation/RunList/QueryApi) (API-03)

**Wave 2** *(blocked on Wave 1; query package and demo-emitter own disjoint files → run in parallel)*

- [x] 04-02-PLAN.md — `query/` transform layer: orphan-tolerant flat DAG assembler + subtree cost roll-up (D-01/D-02/D-03), saturation timeline (D-04), run-list param→filter mapper with validation + outcome derivation (D-05), immutable @Serializable DTOs (API-01/02/03/04)
- [x] 04-03-PLAN.md — Demo emitter: new `demo-emitter` Gradle module, OTel-SDK OTLP client, seeded full-convention nested-subagent span trees with rising saturation, one-shot `main` (D-06) (TOOL-01)

**Wave 3** *(blocked on Wave 2)*

- [x] 04-04-PLAN.md — Query routes over a new isolated `embeddedServer(:8080)` (GET /api/runs, /api/runs/{id}/dag, /api/runs/{id}/saturation) + /healthz, wired into RunningReceivers/shutdown drain; ContentNegotiation isolated from the protobuf OTLP server (API-01/02/03/04, DIST-01)

**Wave 4** *(blocked on Wave 3)*

- [ ] 04-05-PLAN.md — Compose all-in-one: ClickHouse :26.3 LTS → tracer (eclipse-temurin:21-jre, health-gated) → one-shot demo seeder; temurin-jre-safe healthcheck + cold-start smoke (D-07, SC5 manual/CI-Docker verify) (DIST-01)

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
| 1. Attribute Convention & Domain Model | 4/4 | Complete    | 2026-06-14 |
| 2. ClickHouse Storage Layer | 5/5 | Complete    | 2026-06-22 |
| 3. OTLP Ingestion | 3/4 | In Progress|  |
| 4. Query API, Demo Emitter & Compose Stack | 4/5 | In Progress|  |
| 5. Frontend Visualization | 0/TBD | Not started | - |
