# agent-tracer

## What This Is

A purpose-built agent call graph visualizer — Tool 09 of the UnityInFlow ecosystem. It ingests OpenTelemetry traces from KORE (and any OTel-compatible source), stores them in ClickHouse, and renders agent runs as interactive DAGs with token cost overlays, context window saturation timelines, and a replay mode. It replaces Jaeger/Zipkin for agent debugging with agent-native semantics: plan steps, skill activations, context saturation, subagent spawns.

## Core Value

A developer debugging an agent failure can see the full decision tree of any agent run — with token cost and context saturation on every node — instead of reading thousands of generic spans.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] OTLP receiver (gRPC + HTTP/protobuf): ingests traces from KORE and any OTel-compatible source
- [ ] Agent-aware span attribute convention defined and documented by this project: `skill_name`, `context_saturation_pct`, `tokens_used`, `cost_usd`
- [ ] ClickHouse storage for spans, traces, and agent_runs at time-series scale
- [ ] Agent DAG view: full decision tree of any agent run as an interactive graph
- [ ] Token cost overlaid on every node in the DAG
- [ ] Context window saturation timeline
- [ ] Subagent hierarchy: parent-child relationships rendered as nested DAG
- [ ] Replay mode: step through agent decisions with context viewer
- [ ] Filters: by agent, model, cost range, outcome, date range
- [ ] Docker Compose all-in-one: tracer + ClickHouse

### Out of Scope

- Helm chart — distribution target for a later release; v0.0.1 ships Docker Compose only
- agent-memory (Tool 10) integration — Tool 10 doesn't exist yet; `context_saturation_pct` arrives as a span attribute from the emitter, no adapter or stub is built now
- Defining KORE's instrumentation — agent-tracer publishes the attribute convention; instrumenting KORE to emit it is KORE's work
- Generic APM features (HTTP service maps, infra metrics) — Jaeger/Zipkin territory; this tool is agent-native only

## Context

- Part of a 20-tool ecosystem (github.com/UnityInFlow). Ecosystem conventions: MIT license, README/CONTRIBUTING/CI before v0.0.1, >80% test coverage on core logic, self-hosted CI runners (`arc-runner-unityinflow`), Maven group `io.github.unityinflow`.
- Upstream: `kore` (Tool 08, shipped v0.0.1) is the primary trace emitter; `token-dashboard` (Tool 06, shipped v0.1.0) established the ecosystem's Kotlin/Ktor metrics patterns.
- Full feature spec: `09-agent-tracer.md` (in repo root). Acceptance criteria: `CLAUDE.md`.
- Why ClickHouse over PostgreSQL: agent traces are append-only time-series data; columnar storage makes analytics queries (e.g., "10 most expensive agent runs this week") 10–100x faster.
- CI must include the spec-ci-plugin GitHub Action (Tool 04) linting CLAUDE.md on every PR.

## Constraints

- **Tech stack**: Kotlin 2.0+ (JVM 21) + Ktor backend, React 19 + D3.js frontend, ClickHouse storage, OTLP protocol — fixed by ecosystem spec
- **Build order**: Backend must be built and tested before any frontend work — ecosystem rule
- **Build tooling**: Gradle Kotlin DSL only; JUnit 5 + Kotest; coroutines for all async (no `Thread.sleep()`/raw threads); `ktlint` before every commit
- **Kotlin style**: no `var`, no `!!` without a safety comment, sealed classes for domain modelling, immutable data classes
- **Quality**: >80% test coverage on core logic before release
- **Security**: no secrets committed — credentials via environment variables only
- **CI**: UnityInFlow self-hosted runners (`runs-on: [arc-runner-unityinflow]`), never `ubuntu-latest`; runner group requires private repo or enabled public-repo access

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| OTLP receiver supports gRPC + HTTP/protobuf in v0.0.1 | Covers KORE plus virtually every OTel SDK out of the box | — Pending |
| agent-tracer defines the agent span attribute convention | Publishes its own contract; KORE adopts it when instrumented — unblocks this project fully | — Pending |
| Context saturation via span attributes only (no agent-memory dep) | Tool 10 isn't built; emitters supply `context_saturation_pct` directly | — Pending |
| Keep all 10 acceptance criteria in v0.0.1 (incl. replay + filters) | Full checklist as specced; Helm chart is the only deferral | — Pending |
| ClickHouse over PostgreSQL | Columnar storage for append-only time-series trace analytics | — Pending |
| spec-ci-plugin in CI for CLAUDE.md linting | Dogfoods Tool 04; zero ongoing effort | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-06-12 after initialization*
