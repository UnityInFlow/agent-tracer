# agent-tracer

## What This Is

A purpose-built agent call graph visualizer — Tool 09 of the UnityInFlow ecosystem. It ingests OpenTelemetry traces from KORE (and any OTel-compatible source), stores them in ClickHouse, and renders agent runs as interactive DAGs with token cost overlays, context window saturation timelines, and a replay mode. It replaces Jaeger/Zipkin for agent debugging with agent-native semantics: plan steps, skill activations, context saturation, subagent spawns.

## Core Value

A developer debugging an agent failure can see the full decision tree of any agent run — with token cost and context saturation on every node — instead of reading thousands of generic spans.

## Requirements

### Validated

- [x] Agent-aware span attribute convention defined and documented by this project: `skill_name`, `context_saturation_pct`, `tokens_used`, `cost_usd` — *Validated in Phase 1 (CONV-01, CONV-02, CONV-03): published `docs/agent-span-convention.md` v1.0, `agent-semconv` vendored constants, and the pure-Kotlin `AgentSpanExtractor` turning OTLP attributes into typed `AgentSpan` fields*
- [x] ClickHouse storage for spans, traces, and agent_runs at time-series scale — *Validated in Phase 2 (STOR-*): RowBinary batch inserts via client-v2, bounded-channel SpanWriter, schema + rollup MVs. Real-ClickHouse round-trip observed green on real Docker (closed via the Phase-4 compose cold-start this session)*
- [x] OTLP receiver (gRPC + HTTP/protobuf): ingests traces from KORE and any OTel-compatible source — *Validated in Phase 3 (INGEST-01/02/03): standalone `io.grpc.Server` :4317 + Ktor :4318, transport-agnostic `OtlpDecoder`, migrate-then-serve lifecycle. Real-Docker ingest observed green via the Phase-4 compose cold-start*
- [x] Query API: run list with filters, per-run DAG with cost roll-ups, saturation timeline — *Validated in Phase 4 (API-01/02/03/04): isolated Netty :8080 JSON server (off the protobuf OTLP server), orphan-tolerant server-side DAG assembly with subtree cost roll-ups, saturation projection, `{name:Type}`-bound paginated filters*
- [x] Demo emitter: seeded full-convention agent runs over OTLP/HTTP — *Validated in Phase 4 (TOOL-01): deterministic `SeededRunFactory` (invoke_agent → plan → skills → nested subagents → tools) + one-shot `DemoEmitter`; sole producer of saturation data*
- [x] Docker Compose all-in-one: tracer + ClickHouse — *Validated in Phase 4 (DIST-01/SC5): health-gated 3-service cold-start (ClickHouse :26.3 → tracer → one-shot demo seeder); observed green on real Docker — `GET :8080/api/runs` served the seeded runs end-to-end*

### Active

> The backend data/API behind every item below is delivered and verified (Phase 4 Query API + DAG assembly + saturation projection). These remain Active as **frontend render/interaction** criteria for Phase 5 (React 19 + D3.js).

- [ ] Agent DAG view: full decision tree of any agent run as an interactive graph *(server-side assembly done; interactive render pending Phase 5)*
- [ ] Token cost overlaid on every node in the DAG *(cost roll-up computed server-side; overlay render pending Phase 5)*
- [ ] Context window saturation timeline *(projection done server-side; timeline render pending Phase 5)*
- [ ] Subagent hierarchy: parent-child relationships rendered as nested DAG *(INVOKE_AGENT group nodes emitted by the assembler; nested render pending Phase 5)*
- [ ] Replay mode: step through agent decisions with context viewer

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
| OTLP receiver supports gRPC + HTTP/protobuf in v0.0.1 | Covers KORE plus virtually every OTel SDK out of the box | ✓ Validated in Phase 3 |
| agent-tracer defines the agent span attribute convention | Publishes its own contract; KORE adopts it when instrumented — unblocks this project fully | ✓ Validated in Phase 1 |
| Context saturation via span attributes only (no agent-memory dep) | Tool 10 isn't built; emitters supply `context_saturation_pct` directly | — Pending |
| Keep all 10 acceptance criteria in v0.0.1 (incl. replay + filters) | Full checklist as specced; Helm chart is the only deferral | — Pending |
| ClickHouse over PostgreSQL | Columnar storage for append-only time-series trace analytics | ✓ Validated in Phases 2–4 (real-Docker green) |
| spec-ci-plugin in CI for CLAUDE.md linting | Dogfoods Tool 04; zero ongoing effort | — Pending |
| Server-side DAG assembly (frontend gets a render-ready nested tree) | DAG correctness stays inside Kotlin test coverage; thin frontend | ✓ Validated in Phase 4 (API-01/02) |
| Two isolated Ktor/Netty servers — protobuf OTLP (:4317/:4318) + JSON query API (:8080) | `ContentNegotiation { json() }` must never intercept protobuf OTLP bodies (Pitfall 1) | ✓ Validated in Phase 4 (API-01..04) |
| Docker Compose all-in-one delivers DIST-01 with the backend milestone | Cold-start ingest provable with curl before React exists | ✓ Validated in Phase 4 (SC5 cold-start green) |

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
*Last updated: 2026-06-24 — Phase 4 (Query API, Demo Emitter & Compose Stack) complete. Backend milestone done: ingest → store → query → cold-start serve all verified (SC5 green on real Docker). Next: Phase 5 frontend (React 19 + D3.js).*
