# agent-tracer — Purpose-Built Agent Call Graph Visualizer

## Project Overview

**Tool 09** in the [UnityInFlow](https://github.com/UnityInFlow) ecosystem.

Agent-aware trace visualizer that understands plan steps, skill activations, context saturation, and subagent spawns. Renders interactive DAGs with token cost overlays and context window saturation timelines. Replaces Jaeger/Zipkin for agent debugging with agent-native semantics.

**Phase:** 3 | **Stack:** Kotlin (Ktor) + React + D3.js | **Distribution:** Docker Compose + Helm chart

## Status

Planning in progress (GSD initialized June 2026). `token-dashboard` (Tool 06) shipped v0.1.0 — metrics patterns available. `agent-memory` (Tool 10) still planned — context data integration deferred until it exists; do not block planning on it.

## Reference Documents

- `09-agent-tracer.md` — Feature spec, key features checklist, agent DAG view, ClickHouse storage, implementation todos (Month 8)
- `claude-code-harness-engineering-guide-v2.md` — Harness engineering patterns and best practices

Read these before making architectural or scope decisions.

## Tooling

| Tool | Status | Usage |
|---|---|---|
| **GSD** | Installed (global) | `/gsd:new-project` to scaffold when ready. `/gsd:plan-phase` and `/gsd:execute-phase` for structured development. |
| **RTK** | Active (v0.34.2) | Automatic via hooks. Compresses gradle, git output. ~80% token savings. |
| **Superpowers** | Active (v5.0.5) | Auto-triggers brainstorming, TDD, planning, code review, debugging skills. |

## Constraints

### Kotlin — Backend (inherited from ecosystem CLAUDE.md)
- Kotlin 2.0+, JVM target 21
- Gradle (Kotlin DSL only — never Groovy)
- Test with JUnit 5 + Kotest matchers
- Coroutines for all async work — never `Thread.sleep()` or raw threads
- Immutable data classes preferred over mutable state
- Sealed classes for domain modelling
- No `var` — always `val`
- No `!!` without a comment explaining why it's safe
- `ktlint` before every commit
- Group: `io.github.unityinflow` (ecosystem-wide rename from `dev.unityinflow`, May 2026)

### Frontend
- React 19 + D3.js for DAG visualization
- Zustand or similar for state management

### General
- Test coverage >80% on core logic before release
- No secrets committed — all credentials via environment variables

## Acceptance Criteria

Target release: v0.0.1

- [ ] OTLP receiver: ingests traces from KORE and any OTel-compatible source
- [ ] Agent DAG view: full decision tree of any agent run as interactive graph
- [ ] Agent-aware span attributes: skill_name, context_saturation_pct, tokens_used, cost_usd
- [ ] Token cost overlaid on every node in the DAG
- [ ] Context window saturation timeline
- [ ] Subagent hierarchy: parent-child relationships as nested DAG
- [ ] Replay mode: step through agent decisions with context viewer
- [ ] Filters: by agent, model, cost range, outcome, date range
- [ ] ClickHouse storage for time-series trace data at scale
- [ ] Docker Compose all-in-one: tracer + ClickHouse

## Development Workflow

When ready to build:

1. `/gsd:new-project` — describe agent-tracer, feed existing spec
2. `/gsd:discuss-phase 1` — lock in decisions for backend (OTLP receiver, ClickHouse schema, span attribute extraction)
3. `/gsd:plan-phase 1` — atomic task plans with file paths
4. `/gsd:execute-phase 1` — parallel execution with fresh context windows
5. `/gsd:discuss-phase 2` — lock in decisions for frontend (React, D3 DAG, replay mode)
6. `/gsd:plan-phase 2` — atomic task plans
7. `/gsd:execute-phase 2` — build and ship

Superpowers skills (TDD, code review, debugging) activate automatically during execution.

## Key Dependencies (for reference, not installed yet)

- `ktor-server-core` — HTTP server
- `opentelemetry-sdk` — OTLP receiver
- `clickhouse-jdbc` — ClickHouse client
- `react` + `d3` — frontend visualization

---

## CI / Self-Hosted Runners

Use UnityInFlow org-level self-hosted runners. Never use `ubuntu-latest`.

```yaml
runs-on: [arc-runner-unityinflow]
```

CI must include the `spec-ci-plugin` GitHub Action (Tool 04) to lint this CLAUDE.md with spec-linter on every PR (decision: June 2026).

Available runners: `hetzner-runner-1/2/3` (X64), `orangepi-runner` (ARM64).

---

## Do Not

- Do not use `var` in Kotlin — always `val`
- Do not use `!!` without a comment explaining why it's safe
- Do not build the frontend until the backend API is working and tested
- Do not commit secrets or API keys
- Do not skip writing tests
- Do not inline the reference docs into this file — read them by path
