# Requirements: agent-tracer

**Defined:** 2026-06-12
**Core Value:** A developer debugging an agent failure can see the full decision tree of any agent run — with token cost and context saturation on every node — instead of reading thousands of generic spans.

## v1 Requirements

Requirements for v0.0.1. Each maps to roadmap phases.

### Convention

- [x] **CONV-01**: Agent span attribute convention is published as a documented contract: `unityinflow.skill.name`, `unityinflow.context.saturation_pct`, `unityinflow.cost.usd` plus standard `gen_ai.*` token/model/agent attributes
- [x] **CONV-02**: Attribute extractor reads standard OTel GenAI semconv (`gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `invoke_agent`/`execute_tool` operations) so any gen_ai.*-emitting source renders usefully without changes
- [x] **CONV-03**: Convention specifies OTLP value types per attribute and large-payload (prompt/completion) handling rules

### Ingestion

- [ ] **INGEST-01**: OTLP/HTTP-protobuf receiver accepts trace exports on the standard port (4318) with spec-conformant partial_success and error responses
- [ ] **INGEST-02**: OTLP/gRPC receiver accepts trace exports on the standard port (4317), with message size limits raised for prompt-heavy agent spans
- [ ] **INGEST-03**: Spans from both transports flow through one batching pipeline into ClickHouse (no per-request inserts)

### Storage

- [x] **STOR-01**: ClickHouse schema stores spans, traces, and agent_runs with typed first-class columns for agent fields (tokens, cost, saturation, skill, model)
- [x] **STOR-02**: Trace lookup by trace_id is served by a materialized view (no full table scan)
- [x] **STOR-03**: Span input/output payloads are stored, enabling replay and the span detail panel

### Query API

- [ ] **API-01**: API returns any agent run as an assembled DAG (parent-child span tree, assembled at query time)
- [ ] **API-02**: API returns subagent hierarchy (nested parent-child agent relationships) within a run
- [x] **API-03**: API supports filtering runs by agent, model, cost range, outcome, and date range
- [ ] **API-04**: API returns the context-saturation series for a run (timeline data)

### Visualization

- [ ] **VIZ-01**: User can view the full decision tree of any agent run as an interactive DAG
- [ ] **VIZ-02**: Token cost is overlaid on every node in the DAG
- [ ] **VIZ-03**: User can view a context window saturation timeline for a run
- [ ] **VIZ-04**: Subagent hierarchy renders as a nested DAG (collapsible compound nodes)
- [ ] **VIZ-05**: User can step through agent decisions in replay mode with a context viewer
- [ ] **VIZ-06**: User can click any DAG node to open a span detail panel with full attributes and input/output payloads
- [ ] **VIZ-07**: User can filter the run list by agent, model, cost range, outcome, and date range

### Tooling

- [ ] **TOOL-01**: A demo emitter generates realistic synthetic agent-run traces via OTLP for demos, e2e tests, and frontend development

### Distribution

- [ ] **DIST-01**: `docker compose up` starts the all-in-one stack (tracer + ClickHouse) and ingests traces end-to-end on a cold start

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Visualization

- **VIZ-08**: Waterfall/timeline view of spans by duration (most-likely "missing feature" complaint; same data, different layout)

### Distribution

- **DIST-02**: Helm chart for Kubernetes deployment

### Ingestion

- **INGEST-04**: OpenInference attribute mapping (Phoenix ecosystem emitters)
- **INGEST-05**: Cross-trace subagent linkage via span links (revisit when KORE's spawning model is concrete)

## Out of Scope

| Feature | Reason |
|---------|--------|
| Evals / scorers | agent-bench's (Tool 18) job |
| Prompt management / versioning | prompt-vc's (Tool 16) job |
| Alerting / budget enforcement | budget-breaker's (Tool 05) job |
| Proxy/gateway ingestion mode | Conflicts with OTLP-first architecture; heavy infra |
| Per-framework SDK integrations | OTLP + gen_ai.* convention is the integration surface |
| Auth / multi-tenancy | Single-team local/self-hosted tool in v0.0.1 |
| agent-memory (Tool 10) integration | Tool 10 not built; saturation arrives as span attribute |
| KORE instrumentation | KORE's repo adopts the published convention |
| Generic APM (service maps, infra metrics) | Jaeger/Zipkin territory; agent-native only |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CONV-01 | Phase 1 | Complete |
| CONV-02 | Phase 1 | Complete |
| CONV-03 | Phase 1 | Complete |
| STOR-01 | Phase 2 | Complete |
| STOR-02 | Phase 2 | Complete |
| STOR-03 | Phase 2 | Complete |
| INGEST-01 | Phase 3 | Pending |
| INGEST-02 | Phase 3 | Pending |
| INGEST-03 | Phase 3 | Pending |
| API-01 | Phase 4 | Pending |
| API-02 | Phase 4 | Pending |
| API-03 | Phase 4 | Complete |
| API-04 | Phase 4 | Pending |
| TOOL-01 | Phase 4 | Pending |
| DIST-01 | Phase 4 | Pending |
| VIZ-01 | Phase 5 | Pending |
| VIZ-02 | Phase 5 | Pending |
| VIZ-03 | Phase 5 | Pending |
| VIZ-04 | Phase 5 | Pending |
| VIZ-05 | Phase 5 | Pending |
| VIZ-06 | Phase 5 | Pending |
| VIZ-07 | Phase 5 | Pending |

**Coverage:**

- v1 requirements: 22 total (count corrected from 21 during roadmap creation — enumeration is 3 CONV + 3 INGEST + 3 STOR + 4 API + 7 VIZ + 1 TOOL + 1 DIST)
- Mapped to phases: 22
- Unmapped: 0 ✓

---
*Requirements defined: 2026-06-12*
*Last updated: 2026-06-12 after roadmap creation (traceability populated)*
