# Phase 1: Attribute Convention & Domain Model - Context

**Gathered:** 2026-06-12
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers the ecosystem's public contract: a published, versioned agent span attribute convention (`unityinflow.*` extensions layered over standard OTel GenAI semconv `gen_ai.*`) and a pure-Kotlin extractor that turns raw OTLP span attributes into typed agent fields. No storage, no receivers, no frontend — those are Phases 2–5. Requirements: CONV-01, CONV-02, CONV-03.

</domain>

<decisions>
## Implementation Decisions

### Convention publishing & versioning
- **D-01:** The contract lives in this repo as a versioned markdown spec: `docs/agent-span-convention.md`, linked from README. agent-tracer owns and hosts the contract.
- **D-02:** Convention is semver'd starting at **1.0**. Emitters stamp `unityinflow.schema_version` (string) so the extractor can branch on version in the future.
- **D-03:** A Kotlin constants module (`agent-semconv`, group `io.github.unityinflow`) ships typed attribute keys so KORE and other Kotlin emitters depend on the artifact instead of hardcoding strings. Maven Central publishing happens at v0.0.1 release (publishToMavenLocal until then).

### Attribute vocabulary (convention v1.0)
- **D-04:** `unityinflow.context.saturation_pct` — double, **0–100** percent scale. Extractor clamps out-of-range values.
- **D-05:** `unityinflow.cost.usd` — double, USD. Non-USD emitters convert before emitting. No currency pair, no micro-USD integer.
- **D-06:** Token counts come ONLY from standard `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` — no `unityinflow.*` token duplicates.
- **D-07:** v1.0 also includes `unityinflow.plan.step` (int) and `unityinflow.skill.version` (string) alongside `unityinflow.skill.name`. No broader reserved vocabulary.

### Run identity & outcome semantics
- **D-08:** **Trace = run.** One OTel trace is one agent run; the root span is the run's entry point. No separate run-id attribute.
- **D-09:** Outcome defaults to the root span's OTel status (OK → success, ERROR → failure); an optional `unityinflow.outcome` attribute (`success|failure|partial|cancelled`) overrides when the emitter knows better.
- **D-10:** Agent identity = `gen_ai.agent.name` when present, falling back to OTel resource `service.name` — generic sources still get a usable agent dimension.

### Extraction precedence & payload rules
- **D-11:** When `unityinflow.*` and `gen_ai.*` both supply the same field and disagree, **`unityinflow.*` wins** (specific extension over generic standard). Precedence must be verified by tests — this is a roadmap success criterion.
- **D-12:** Missing fields are **nullable typed Kotlin fields** (e.g. `cost: Double?`). No sentinel defaults — absence is information; aggregates skip nulls.
- **D-13:** Payload cap: **256 KB per payload field**. Over-cap payloads are truncated by the receiver with `unityinflow.payload.truncated=true` and the original size recorded. Replay surfaces truncation honestly.
- **D-14:** Prompt/completion payloads follow **standard OTel GenAI semconv message content** (gen_ai input/output messages) — no custom `unityinflow.payload.*` content attributes. The convention doc adds only the size/truncation rules on top.

### Claude's Discretion
- Kotlin module layout (single vs multi-module Gradle structure), domain model class design, extractor API shape, test structure — standard ecosystem conventions apply (sealed classes, immutable data classes, no `var`, JUnit 5 + Kotest).
- Exact prose structure of the convention document.
- Whether gen_ai semconv attribute keys are vendored as constants or referenced from an upstream artifact — research should check what `opentelemetry-semconv` ships.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope & requirements
- `.planning/REQUIREMENTS.md` — CONV-01..03 definitions
- `.planning/ROADMAP.md` — Phase 1 goal and the 4 success criteria (incl. test-verified precedence)

### Research (directly load-bearing for this phase)
- `.planning/research/ARCHITECTURE.md` — gen_ai.* semconv mapping analysis, convention-as-superset rationale, warning that gen_ai semconv is Development status (isolate keys in one package)
- `.planning/research/STACK.md` — verified library versions (Kotlin 2.4.0, opentelemetry-proto 1.10.0-alpha, Gradle 9.5.1, JUnit 5.14.4, Kotest 6.1.11)
- `.planning/research/PITFALLS.md` — attribute typing pitfall (agent fields must become typed columns in Phase 2; the convention must specify OTLP value types per attribute)

### Ecosystem rules
- `CLAUDE.md` — Kotlin constraints, group `io.github.unityinflow`, CI runner rules
- `09-agent-tracer.md` — original feature spec

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None — greenfield repo, no source code yet. This phase creates the Gradle build and first Kotlin modules.

### Established Patterns
- Ecosystem Kotlin conventions (from CLAUDE.md): Gradle Kotlin DSL, version catalog, sealed classes for domain modelling, `Result<T>` over exceptions for expected failures, ktlint.
- Sibling tools `token-dashboard` (06) and `kore` (08) are prior art for Kotlin/Gradle setup and Maven Central publishing under `io.github.unityinflow`.

### Integration Points
- The `agent-semconv` constants module is the contract surface KORE will depend on.
- The extractor's typed output model feeds Phase 2's ClickHouse column design — the convention's per-attribute OTLP value types gate the schema.

</code_context>

<specifics>
## Specific Ideas

- The convention doc is positioned as an ecosystem contract, not internal documentation — it's what KORE's instrumentation work will be built against.
- "Any OTel-compatible source renders usefully" is the bar: a span with only standard `gen_ai.*` attributes must extract into typed agent fields with zero emitter changes.

</specifics>

<deferred>
## Deferred Ideas

- OpenInference attribute mapping (Phoenix ecosystem) — tracked as v2 requirement INGEST-04
- Cross-trace subagent linkage via span links — tracked as v2 requirement INGEST-05; revisit when KORE's spawning model is concrete
- Hash-based payload deduplication beyond the cap — needs a blob store; not in v0.0.1

</deferred>

---

*Phase: 1-Attribute Convention & Domain Model*
*Context gathered: 2026-06-12*
