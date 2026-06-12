# Feature Research

**Domain:** Agent-native trace visualization / LLM & agent observability
**Researched:** 2026-06-12
**Confidence:** MEDIUM-HIGH (official docs for Langfuse/LangSmith/Phoenix/OTel = HIGH; market-positioning claims from vendor blogs = MEDIUM)

## Market Context

The agent observability market splits into three camps, and agent-tracer sits deliberately between them:

1. **Generic distributed tracers** (Jaeger, Zipkin, Grafana Tempo) — OTLP-native, great span timelines and search, zero agent semantics. The community consensus (including Jaeger's own ecosystem) is that agent debugging through generic spans is a "black box" without GenAI-aware visualization.
2. **LLM observability platforms** (Langfuse, LangSmith, Phoenix, Braintrust, Helicone) — full LLMOps suites: tracing + evals + prompt management + playgrounds + datasets + annotations. Tracing is one feature among many; agent-graph views exist but are mostly framework-coupled (e.g., Langfuse's agent graph is auto-shown for LangGraph).
3. **Agent-ops tools** (AgentOps) — session waterfall, time-travel replay, multi-agent nesting, cost tracking. Closest to agent-tracer's vision, but SaaS-first and SDK-coupled (Python SDK, 400+ framework integrations) rather than OTLP-first.

**Key market signal:** OpenTelemetry GenAI semantic conventions (`gen_ai.*`) are now the convergence point — `invoke_agent` / `execute_tool` span kinds, `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens`, `gen_ai.agent.name`, `gen_ai.request.model`, captured `gen_ai.input.messages` / `gen_ai.output.messages`. Datadog, Jaeger v2, Phoenix, and LangSmith all align with these. Any new tracer that invents a parallel attribute vocabulary without mapping `gen_ai.*` will reject the instrumentation the ecosystem is already emitting.

**Key gap confirmed:** Context-window saturation tracking is a recognized best practice in 2026 agent-observability writing (Galileo, Sentry, DataRobot guides) but **no major tool ships a first-class saturation timeline**. Platforms track token usage; none visualize "when did context start degrading" as a dedicated view. This is agent-tracer's most defensible differentiator.

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = product feels incomplete.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| OTLP ingest (gRPC + HTTP/protobuf) | Every modern tracer (Jaeger, Tempo, Phoenix, Langfuse) accepts OTLP; it's the integration contract | MEDIUM | ✅ In v0.0.1 scope. HTTP/protobuf matters because many SDKs default to it |
| Trace list with search & filters | Jaeger tag-filtering, LangSmith tree-aware queries, Langfuse metadata filters — universal | MEDIUM | ✅ In scope (agent, model, cost range, outcome, date). Outcome/status filter is the one users hit first when debugging failures |
| Hierarchical span tree / waterfall timeline | The default debugging view in Jaeger, LangSmith ("trace tree"), AgentOps ("session waterfall"), Phoenix. Users expect to see *where time went* before they care about decision structure | MEDIUM | ⚠️ **Gap in current scope.** The DAG view shows decision structure, not duration. A duration-ordered waterfall is the view every competitor leads with. Cheap to add once spans are stored; recommend v0.1 at latest |
| Span detail panel with full input/output payloads | Phoenix shows full prompts, system instructions, tool args, outputs per span; Langfuse and LangSmith the same. "What did the LLM actually see/say" is the #1 debugging question | MEDIUM | ⚠️ **Partial gap.** Replay mode's "context viewer" implies this, but click-a-node → see full I/O + attributes + errors should be an explicit requirement independent of replay. Requires storing `gen_ai.input.messages`/`gen_ai.output.messages` (payload size implications for ClickHouse schema) |
| Token usage + cost per span and per trace (aggregated roll-ups) | Langfuse, LangSmith, Braintrust, Helicone all do per-request cost with trace-level aggregation; LangSmith rolls child costs up to parents | LOW-MEDIUM | ✅ In scope via cost overlay. Ensure roll-up semantics (parent shows sum of subtree) — that's what users expect from LangSmith's trace tree |
| Error/failure status surfaced visually | Jaeger marks error spans; AgentOps has failure detection; every tool color-codes errors | LOW | Implied by "outcome" filter — make sure failed nodes are visually distinct in the DAG too |
| OTel GenAI semantic convention compatibility | The ecosystem (Jaeger v2, Datadog, Phoenix, OpenLLMetry/Traceloop) is converging on `gen_ai.*`. Emitters instrumented to semconv should "just work" | MEDIUM | ⚠️ **Important gap.** The custom convention (`skill_name`, `context_saturation_pct`, `tokens_used`, `cost_usd`) should be defined as an *extension* of `gen_ai.*`, with the extractor mapping `gen_ai.usage.input_tokens`/`output_tokens`, `gen_ai.agent.name`, `gen_ai.request.model`, `execute_tool`/`invoke_agent` operations. Otherwise non-KORE OTel sources ingest but render as empty DAGs |
| One-command self-host (Docker Compose) | Table stakes for OSS observability — Phoenix, Langfuse, Jaeger all have it; it's the first README command | LOW-MEDIUM | ✅ In scope |
| Trace permalink / shareable deep link | Standard in Jaeger/Langfuse/LangSmith; debugging is collaborative ("look at this run") | LOW | Not in scope; trivial if routing is URL-driven from day one — make it a frontend convention, not a feature |

### Differentiators (Competitive Advantage)

Features that set the product apart. Not required, but valuable.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Agent DAG decision-tree view | Core value. Langfuse's agent graph is framework-coupled (auto-inferred, best with LangGraph); Jaeger has no agent semantics. A framework-agnostic, attribute-driven decision DAG is the differentiated default view | HIGH | ✅ In scope. D3 force/dagre layout over span parent-child + agent attributes. The differentiation is *agent semantics on nodes* (skill, cost, saturation), not the graph itself |
| Context window saturation timeline | **No major competitor ships this.** Recognized best practice ("spot when agents operate with incomplete information") with zero first-class tooling. Strongest unique claim for launch messaging | MEDIUM | ✅ In scope. Depends entirely on emitters supplying `context_saturation_pct` — value is gated on KORE adopting the convention |
| Replay mode (step through decisions with context viewer) | AgentOps' time-travel replay is its flagship SaaS feature; no OSS self-hosted equivalent exists. "Rewind to the exact step where reasoning went sideways" | HIGH | ✅ In scope. Highest-complexity item in v0.0.1; requires full payload storage + ordered step model. This is the feature most at risk of slipping — consider a minimal version (ordered step-through of stored spans) before fancy state reconstruction |
| Subagent hierarchy as nested DAG | Multi-agent systems are the 2026 growth area; AgentOps nests sub-agent spans, but nobody renders parent-child agent runs as collapsible nested DAGs | MEDIUM | ✅ In scope. Falls out of `invoke_agent` span nesting if the convention captures parent agent run ID |
| Token cost overlay on every DAG node | Competitors show cost in tables/trees; cost *on the graph* makes expensive branches visually obvious ("the 10 most expensive decisions, at a glance") | LOW-MEDIUM | ✅ In scope. Cheap once DAG + attributes exist; high demo value |
| ClickHouse-scale cost analytics ("10 most expensive runs this week") | Phoenix defaults to SQLite; Langfuse self-host needs ClickHouse + Redis + Postgres + S3 (heavy). A single ClickHouse + tracer compose with fast analytics is a real ops-simplicity edge | MEDIUM | ✅ In scope via storage choice. Surface at least one canned analytics query in the UI to prove the point |
| Published agent span attribute convention (spec as a product) | Owning the convention makes agent-tracer the reference implementation for the whole UnityInFlow ecosystem (KORE, budget-breaker, token-dashboard all emit it) | LOW | ✅ In scope. Publish as a versioned markdown spec; explicitly position as a `gen_ai.*` extension |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create problems.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Evals / LLM-as-judge / scorers | Every LLMOps platform bundles them (Langfuse, LangSmith, Braintrust, Phoenix); users ask "can it tell me if the run was *good*?" | Entirely different product: datasets, scorer runtimes, judge-model API keys, eval UX. Braintrust is a whole company built on this. Would dilute the "purpose-built tracer" identity | Expose outcome/status as a filterable span attribute; let `agent-bench` (Tool 18) own evaluation |
| Prompt management / playground / datasets / annotation queues | Langfuse and LangSmith train users to expect the full LLMOps suite | Massive scope (versioning, editor UX, secrets for provider keys, human workflows); none of it serves "debug this failed run" | Stay tracer-only; `prompt-vc` (Tool 16) owns prompt versioning in the ecosystem |
| Generic APM (service maps, infra metrics, logs correlation) | "Since you ingest OTLP, why not show my HTTP services too?" | Jaeger/Tempo/Grafana do this better and always will; agent semantics get buried under generic spans | Already in Out of Scope. Ingest non-agent spans gracefully (don't crash), render them as plain child nodes, but build no features for them |
| Proxy/gateway mode (Helicone-style: route LLM traffic through the tracer) | One-line integration appeal; enables caching and exact cost capture | Puts the tracer on the request hot path (latency, availability blast radius); conflicts with OTLP-first architecture; Helicone needed Cloudflare Workers + Kafka to make it work | OTLP sidecar/exporter model only — observability must never gate agent traffic |
| Per-framework auto-instrumentation SDKs (LangChain/CrewAI/AutoGen adapters) | AgentOps' "400+ integrations" is its marketing; users want zero-config | An unbounded maintenance treadmill in languages outside the stack (Python/TS); OpenInference and OpenLLMetry already exist and emit OTLP | Accept standard OTLP + `gen_ai.*`; document "works with OpenLLMetry/OpenInference emitters"; KORE is the first-party emitter |
| Alerting / anomaly detection / SLOs | "Tell me when cost spikes" is a natural ask | Requires notification channels, threshold config, evaluation loops — monitoring-product territory; `budget-breaker` (Tool 05) already owns cost enforcement in the ecosystem | Expose ClickHouse for external Grafana dashboards/alerts; document the integration |
| Multi-tenancy / RBAC / SSO in v0.0.1 | Enterprise checkbox thinking | Auth before product-market fit is pure drag; self-hosted single-team is the OSS adoption path (Phoenix launched the same way) | Single-tenant v0.0.1 behind the user's own network; revisit at Helm-chart stage |
| Real-time live streaming of in-flight runs | Watching the agent "think" live is a compelling demo | WebSocket/SSE infra, partial-trace rendering, out-of-order span handling — large complexity for marginal debugging value (debugging is post-hoc 95% of the time) | Near-real-time via polling/refresh on completed spans; revisit if users demand it |

## Feature Dependencies

```
[Attribute convention spec (gen_ai.* extension)]
    └──required by──> [Span attribute extractor]
                          └──required by──> [Agent DAG view]
                          └──required by──> [Cost overlay]
                          └──required by──> [Saturation timeline]
                          └──required by──> [Filters (agent/model/cost/outcome)]

[OTLP receiver] ──required by──> [ClickHouse storage] ──required by──> everything downstream

[ClickHouse storage (incl. payload columns for input/output messages)]
    └──required by──> [Span detail panel]
                          └──required by──> [Replay mode context viewer]

[Agent DAG view]
    └──required by──> [Subagent nested DAG]
    └──required by──> [Cost overlay on nodes]
    └──enhanced by──> [Replay mode] (replay steps through the DAG)

[Filters] ──require──> ClickHouse schema with indexed/materialized agent columns
[Waterfall view] ──shares data with──> [Agent DAG view] (same spans, different layout)
```

### Dependency Notes

- **Convention before extractor before everything:** Five of the ten acceptance criteria are downstream of the attribute convention. It must be the first artifact, and mapping `gen_ai.*` at extractor level decides whether non-KORE sources render meaningfully.
- **Replay requires payload storage decided in the ClickHouse schema:** If `gen_ai.input.messages`/`output.messages` aren't stored (or are truncated) in the v0.0.1 schema, replay's context viewer has nothing to show. Schema design must account for large message payloads (compression, separate column/table) up front — retrofitting columnar schemas is painful.
- **Saturation timeline is emitter-gated:** Zero value until KORE (or any emitter) sends `context_saturation_pct`. Ship a demo emitter/sample-data generator alongside so the feature is demonstrable at launch.
- **Waterfall and DAG conflict for "default view" attention, not data:** Same span data, two layouts. DAG is the differentiator and should be default; waterfall is the familiarity bridge for Jaeger refugees.

## MVP Definition

### Launch With (v0.0.1) — validates current scope

The 10 acceptance criteria are broadly correct for the market. Confirmed essential:

- [ ] OTLP receiver (gRPC + HTTP/protobuf) — integration contract; table stakes
- [ ] Attribute convention **published as a `gen_ai.*` extension with extractor-level mapping** — table stakes amendment; without it, "any OTel-compatible source" is true for ingest but false for visualization
- [ ] ClickHouse storage incl. message payload columns — foundation; replay depends on payload decision
- [ ] Agent DAG view + cost overlay + subagent nesting — the core differentiator cluster
- [ ] Span detail panel (full I/O, attributes, errors on node click) — **recommend adding as explicit criterion**; cheap once payloads are stored, and the #1 debugging interaction in every competitor
- [ ] Context saturation timeline — unique differentiator, lead launch messaging with it
- [ ] Filters (agent, model, cost, outcome, date) — table stakes
- [ ] Replay mode — keep, but scope minimally (ordered step-through + context viewer; no state reconstruction)
- [ ] Docker Compose all-in-one — table stakes for OSS adoption
- [ ] Sample-data/demo emitter — **recommend adding**; every criterion above is undemonstrable without traces, and KORE instrumentation is out of scope

### Add After Validation (v0.1.x)

- [ ] Waterfall/timeline span view — trigger: first user feedback asking "where did the time go" (it will come; every competitor leads with this view)
- [ ] Trace permalinks/deep links — trigger: immediately, if not free from URL routing
- [ ] Session grouping (multiple traces per conversation/session, Langfuse-style) — trigger: users running multi-turn agents asking to see runs grouped
- [ ] Canned analytics dashboard (most expensive runs, cost by model/agent over time) — trigger: proving the ClickHouse choice publicly
- [ ] Trace comparison (run A vs run B side by side) — trigger: users debugging regressions between agent versions

### Future Consideration (v0.2+)

- [ ] Helm chart — already deferred; trigger: first k8s-deploying user
- [ ] Retention policies / TTL management UI — ClickHouse TTLs configured manually until then
- [ ] OpenInference attribute mapping (Phoenix ecosystem compatibility) — broadens emitter base
- [ ] Live/streaming in-flight trace view — only if post-hoc debugging proves insufficient
- [ ] Auth/multi-tenancy — only with hosted or enterprise demand

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| OTLP receiver | HIGH | MEDIUM | P1 |
| Convention spec + gen_ai.* mapping | HIGH | LOW | P1 |
| ClickHouse schema (with payloads) | HIGH | MEDIUM | P1 |
| Agent DAG view | HIGH | HIGH | P1 |
| Span detail panel (I/O viewer) | HIGH | MEDIUM | P1 |
| Cost overlay on nodes | HIGH | LOW | P1 |
| Filters | HIGH | MEDIUM | P1 |
| Saturation timeline | MEDIUM-HIGH (unique) | MEDIUM | P1 |
| Subagent nested DAG | MEDIUM | MEDIUM | P1 |
| Docker Compose | HIGH | LOW | P1 |
| Replay mode (minimal) | MEDIUM | HIGH | P1 (scoped down) |
| Demo emitter / sample data | HIGH (for adoption) | LOW | P1 |
| Waterfall view | MEDIUM | LOW-MEDIUM | P2 |
| Permalinks | MEDIUM | LOW | P2 |
| Session grouping | MEDIUM | MEDIUM | P2 |
| Analytics dashboard | MEDIUM | MEDIUM | P2 |
| Trace comparison | MEDIUM | MEDIUM | P3 |
| Helm chart | LOW (now) | MEDIUM | P3 |
| Live streaming view | LOW | HIGH | P3 |

## Competitor Feature Analysis

| Feature | Langfuse | LangSmith | Phoenix (Arize) | AgentOps | Jaeger/Tempo | Our Approach |
|---------|----------|-----------|------------------|----------|--------------|--------------|
| Agent graph view | Auto-inferred graph, best with LangGraph | Trace tree (hierarchical, not graph) | Span tree with agent/LLM/tool spans | Waterfall, not DAG | Service dep graph (not agent-aware) | Framework-agnostic decision DAG driven by published attribute convention |
| Token/cost tracking | Per-generation usage + cost, dashboards by user/session/model | Auto cost for major providers, roll-ups in trace tree | Token counts on spans | LLM cost tracking per session | None | Cost on every DAG node + ClickHouse analytics; map `gen_ai.usage.*` |
| Context saturation | No (token usage only) | No | No | No | No | First-class saturation timeline (unique) |
| Replay | Session replay (grouped traces) | No step-through | No | Time-travel replay (flagship) | No | OSS self-hosted step-through replay with context viewer |
| Subagent nesting | Nested observations | Child runs | Parent-child spans | Nested sub-agent spans | Generic span nesting | Nested collapsible DAG keyed on `invoke_agent` hierarchy |
| Ingest protocol | OTel + SDKs | SDK-first, OTLP supported | OTel-native (OpenInference) | Python SDK (OTel-based) | OTLP-native | OTLP-only (gRPC + HTTP/protobuf), no SDK treadmill |
| Storage (self-host) | ClickHouse + Postgres + Redis + S3 (heavy) | SaaS (SmithDB) | SQLite default | SaaS | Cassandra/ES/Badger; object storage (Tempo) | Single ClickHouse + tracer Compose — minimal ops |
| Evals/prompt mgmt | Full suite | Full suite | Evals | No | No | Deliberately none (anti-feature) |

## Sources

- [Langfuse Agent Graphs](https://langfuse.com/docs/observability/features/agent-graphs), [Token & Cost Tracking](https://langfuse.com/docs/observability/features/token-and-cost-tracking), [Sessions](https://langfuse.com/docs/observability/features/sessions), [Tracing overview](https://langfuse.com/docs/tracing) — HIGH confidence (official docs)
- [LangSmith Observability](https://www.langchain.com/langsmith/observability), [Cost tracking docs](https://docs.langchain.com/langsmith/cost-tracking), [SmithDB announcement](https://www.langchain.com/blog/introducing-smithdb), [Agent observability guide](https://www.langchain.com/resources/agent-observability) — HIGH confidence (official)
- [Arize Phoenix](https://arize.com/phoenix/), [Phoenix tracing docs](https://arize.com/docs/phoenix/tracing/llm-traces), [Phoenix GitHub](https://github.com/arize-ai/phoenix) — HIGH confidence (official)
- [OTel GenAI agent span conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/), [GenAI spans](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/), [GenAI observability blog (2026)](https://opentelemetry.io/blog/2026/genai-observability/) — HIGH confidence (spec)
- [AgentOps GitHub](https://github.com/agentops-ai/agentops), [AgentOps docs](https://docs.agentops.ai/) — HIGH confidence (official); session waterfall and time-travel replay claims cross-checked with third-party reviews — MEDIUM
- [Jaeger vs Tempo (SigNoz)](https://signoz.io/blog/jaeger-vs-tempo/), [Last9 comparison](https://last9.io/blog/grafana-tempo-vs-jaeger/) — MEDIUM confidence (third-party, consistent)
- [Helicone platform guide](https://www.helicone.ai/blog/the-complete-guide-to-LLM-observability-platforms), [Braintrust observability guide](https://www.braintrust.dev/articles/llm-observability-guide) — MEDIUM confidence (vendor blogs, cross-checked)
- [Jaeger v2 + GenAI conventions for agents (dev.to)](https://dev.to/thegatewayguy/ai-agents-are-opaque-jaeger-v2-otel-genai-conventions-are-the-fix-48b8) — MEDIUM confidence (community, aligns with OTel spec direction)
- Context saturation gap: [Galileo agent observability guide](https://galileo.ai/blog/ai-agent-observability), [Sentry agent monitoring guide](https://blog.sentry.io/ai-agent-observability-developers-guide-to-agent-monitoring/), [Maxim context window management](https://www.getmaxim.ai/articles/context-window-management-strategies-for-long-context-ai-agents-and-chatbots/) — MEDIUM confidence (practice recognized; absence of first-class tooling verified by checking each competitor's feature docs)

---
*Feature research for: agent-native trace visualization (agent-tracer, Tool 09)*
*Researched: 2026-06-12*
