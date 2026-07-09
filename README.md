# agent-tracer

**Tool 09 of the [UnityInFlow](https://github.com/UnityInFlow) ecosystem.**

A purpose-built agent call graph visualizer. agent-tracer ingests OpenTelemetry
traces from [KORE](https://github.com/UnityInFlow/kore) and any OTel-compatible
source, stores them in ClickHouse, and renders agent runs as interactive DAGs with
token-cost overlays, context-window saturation timelines, and a replay mode — agent-native
semantics (plan steps, skill activations, context saturation, subagent spawns)
instead of generic Jaeger/Zipkin spans.

## Quickstart

```bash
git clone https://github.com/UnityInFlow/agent-tracer
cd agent-tracer
docker compose -f docker/compose.yaml up
```

This pulls **only released GHCR images** (`ghcr.io/unityinflow/agent-tracer`,
`ghcr.io/unityinflow/agent-tracer-demo-emitter` and the pinned ClickHouse LTS) —
nothing is built from source. The stack cold-starts ClickHouse, the tracer, and a
one-shot demo-emitter that seeds example agent traces.

Then open http://localhost:8080 — the seeded demo traces are served at
[`/api/runs`](http://localhost:8080/api/runs) (the query API; the visual DAG UI
ships with the frontend milestone). OTLP ingest listens on `:4317` (gRPC) and
`:4318` (HTTP).

To build from source instead, use the dev override:

```bash
docker compose -f docker/compose.yaml -f docker/compose.build.yaml up --build
```

## The Convention

agent-tracer is built against a public, versioned contract — a superset of the
OpenTelemetry GenAI semantic conventions (`gen_ai.*`) plus three `unityinflow.*`
extensions (cost, context saturation, skill):

➡️ **[docs/agent-span-convention.md](docs/agent-span-convention.md)** — the agent
span attribute convention (v1.0).

The machine-readable form of the contract ships as the
`io.github.unityinflow:agent-semconv` artifact: typed attribute-key constants so
emitters depend on the artifact instead of hardcoding strings.

## Status

v0.1.0 — backend released: OTLP ingest (gRPC + HTTP), ClickHouse storage, query
API, demo-emitter, and the Docker Compose quickstart. The React DAG frontend is
the next milestone.

## License

MIT
