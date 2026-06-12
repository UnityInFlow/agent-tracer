# Tool 09: `agent-tracer`
## Purpose-Built Agent Call Graph Visualizer — Deep Dive

> **Phase:** 3 · **Effort:** 6/10 · **Impact:** 9/10 · **Stack:** Kotlin (Ktor) + React + D3  
> **Repo name:** `agent-tracer` · **Build in:** Month 8

---

## 1. Problem Statement

Jaeger and Zipkin are built for microservices HTTP spans. They do not understand agent concepts: plan steps, skill activations, context saturation, subagent spawns. Debugging an agent failure means reading thousands of generic spans with no agent-native semantics. The concept of an agent-aware trace visualizer does not exist.

---

## 2. Key Features — v0.1 Checklist

- [ ] OTLP receiver: ingests traces from KORE and any OTel-compatible source
- [ ] Agent DAG view: full decision tree of any agent run as interactive graph
- [ ] Agent-aware span attributes: skill_name, context_saturation_pct, tokens_used, cost_usd
- [ ] Token cost overlaid on every node in the DAG
- [ ] Context window saturation timeline: see when context starts degrading
- [ ] Subagent hierarchy: parent-child relationships as nested DAG
- [ ] Replay mode: step through agent decisions with context viewer
- [ ] Filters: by agent, model, cost range, outcome, date range
- [ ] ClickHouse storage for time-series trace data at scale
- [ ] Docker Compose all-in-one: tracer + ClickHouse

---

## 3. Technical Stack

**Backend:** Kotlin (Ktor)  
**Frontend:** React 19 + D3.js (DAG visualization)  
**Storage:** ClickHouse (optimized for time-series trace data)  
**Protocol:** OTLP (OpenTelemetry Protocol)  
**Distribution:** Docker Compose (all-in-one) + Helm chart  

**Why ClickHouse over PostgreSQL?**  
Agent traces are append-only time-series data. ClickHouse's columnar storage and compression make queries over millions of spans 10–100x faster than PostgreSQL for analytics queries like 'show me the 10 most expensive agent runs this week'.

---

## 4. Key Implementation Todos

### Month 8 Core
- [ ] OTLP gRPC receiver in Ktor
- [ ] ClickHouse schema for spans, traces, agent_runs
- [ ] Custom span attribute extractor for agent-specific fields
- [ ] React app scaffold with D3 DAG component
- [ ] D3 directed acyclic graph renderer
- [ ] Token cost overlay on nodes
- [ ] Context saturation timeline view
- [ ] Replay mode: step through decisions
- [ ] Docker Compose: tracer + ClickHouse

---

## 5. Success Metrics

| Metric | First Release Target | 3 Month Target |
|---|---|---|
| GitHub stars | 100 | 500 |
| Docker pulls | 50 | 500 |
| OTel spans ingested daily | 1,000 | 100,000 |

---

*Part of the AI Agent Tooling Ecosystem · See 00-MASTER-ANALYSIS.md for full context*
