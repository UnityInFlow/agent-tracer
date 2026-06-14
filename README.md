# agent-tracer

**Tool 09 of the [UnityInFlow](https://github.com/UnityInFlow) ecosystem.**

A purpose-built agent call graph visualizer. agent-tracer ingests OpenTelemetry
traces from [KORE](https://github.com/UnityInFlow/kore) and any OTel-compatible
source, stores them in ClickHouse, and renders agent runs as interactive DAGs with
token-cost overlays, context-window saturation timelines, and a replay mode — agent-native
semantics (plan steps, skill activations, context saturation, subagent spawns)
instead of generic Jaeger/Zipkin spans.

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

Planning and Phase 1 (attribute convention & domain model) in progress.

## License

MIT
