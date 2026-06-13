# Phase 1: Attribute Convention & Domain Model - Research

**Researched:** 2026-06-13
**Domain:** OpenTelemetry GenAI semantic conventions (`gen_ai.*`), agent span attribute design, OTLP value-type mapping, pure-Kotlin attribute extraction
**Confidence:** HIGH (gen_ai.* attribute names, types, opt-in/stability, operation enum, and message-content rules verified against the live OpenTelemetry GenAI semconv repo on 2026-06-13; OTLP AnyValue type system and Kotlin module shape MEDIUM/ASSUMED)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Convention publishing & versioning**
- **D-01:** The contract lives in this repo as a versioned markdown spec: `docs/agent-span-convention.md`, linked from README. agent-tracer owns and hosts the contract.
- **D-02:** Convention is semver'd starting at **1.0**. Emitters stamp `unityinflow.schema_version` (string) so the extractor can branch on version in the future.
- **D-03:** A Kotlin constants module (`agent-semconv`, group `io.github.unityinflow`) ships typed attribute keys so KORE and other Kotlin emitters depend on the artifact instead of hardcoding strings. Maven Central publishing happens at v0.0.1 release (publishToMavenLocal until then).

**Attribute vocabulary (convention v1.0)**
- **D-04:** `unityinflow.context.saturation_pct` — double, **0–100** percent scale. Extractor clamps out-of-range values.
- **D-05:** `unityinflow.cost.usd` — double, USD. Non-USD emitters convert before emitting. No currency pair, no micro-USD integer.
- **D-06:** Token counts come ONLY from standard `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens` — no `unityinflow.*` token duplicates.
- **D-07:** v1.0 also includes `unityinflow.plan.step` (int) and `unityinflow.skill.version` (string) alongside `unityinflow.skill.name`. No broader reserved vocabulary.

**Run identity & outcome semantics**
- **D-08:** **Trace = run.** One OTel trace is one agent run; the root span is the run's entry point. No separate run-id attribute.
- **D-09:** Outcome defaults to the root span's OTel status (OK → success, ERROR → failure); an optional `unityinflow.outcome` attribute (`success|failure|partial|cancelled`) overrides when the emitter knows better.
- **D-10:** Agent identity = `gen_ai.agent.name` when present, falling back to OTel resource `service.name` — generic sources still get a usable agent dimension.

**Extraction precedence & payload rules**
- **D-11:** When `unityinflow.*` and `gen_ai.*` both supply the same field and disagree, **`unityinflow.*` wins** (specific extension over generic standard). Precedence must be verified by tests — this is a roadmap success criterion.
- **D-12:** Missing fields are **nullable typed Kotlin fields** (e.g. `cost: Double?`). No sentinel defaults — absence is information; aggregates skip nulls.
- **D-13:** Payload cap: **256 KB per payload field**. Over-cap payloads are truncated by the receiver with `unityinflow.payload.truncated=true` and the original size recorded. Replay surfaces truncation honestly.
- **D-14:** Prompt/completion payloads follow **standard OTel GenAI semconv message content** (gen_ai input/output messages) — no custom `unityinflow.payload.*` content attributes. The convention doc adds only the size/truncation rules on top.

### Claude's Discretion
- Kotlin module layout (single vs multi-module Gradle structure), domain model class design, extractor API shape, test structure — standard ecosystem conventions apply (sealed classes, immutable data classes, no `var`, JUnit 5 + Kotest).
- Exact prose structure of the convention document.
- Whether gen_ai semconv attribute keys are vendored as constants or referenced from an upstream artifact — research should check what `opentelemetry-semconv` ships. **(Researched — see Open Questions Q1; recommendation: vendor the keys.)**

### Deferred Ideas (OUT OF SCOPE)
- OpenInference attribute mapping (Phoenix ecosystem) — tracked as v2 requirement INGEST-04.
- Cross-trace subagent linkage via span links — tracked as v2 requirement INGEST-05; revisit when KORE's spawning model is concrete.
- Hash-based payload deduplication beyond the cap — needs a blob store; not in v0.0.1.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CONV-01 | Agent span attribute convention published as a documented contract: `unityinflow.skill.name`, `unityinflow.context.saturation_pct`, `unityinflow.cost.usd` + standard `gen_ai.*` token/model/agent attributes | "Standard Stack" maps every `unityinflow.*` extension onto the verified `gen_ai.*` baseline; "Convention Document Contents" gives the exact attribute table + OTLP value type per attribute the doc must publish |
| CONV-02 | Attribute extractor reads standard OTel GenAI semconv (`gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `invoke_agent`/`execute_tool` operations) so any `gen_ai.*`-emitting source renders usefully without changes | "gen_ai.* Attribute Reference" gives verified names/types/enum values; "Extractor Architecture" gives the priority-ordered lookup pattern; "Code Examples" shows the typed-field extraction with precedence |
| CONV-03 | Convention specifies OTLP value types per attribute and large-payload (prompt/completion) handling rules | "OTLP Value-Type Mapping" maps each attribute to an OTLP `AnyValue` variant; "Payload Handling Rules" derives the size/truncation contract from the verified gen_ai message-content opt-in rules (messages are type `any`, recorded as JSON string on spans) |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

These directives carry locked-decision authority. Research recommendations below comply with all of them.

- Kotlin 2.0+ (use 2.4.0 per STACK.md), JVM target 21.
- Gradle **Kotlin DSL only** (never Groovy); use a version catalog (`gradle/libs.versions.toml`).
- Test with **JUnit 5 + Kotest matchers** (Kotest assertions-only, JUnit 5 as runner). Not JUnit 6.
- Coroutines for all async — **no `Thread.sleep()` or raw threads**. (This phase is pure/synchronous; no async needed.)
- **Immutable data classes** preferred over mutable state; **sealed classes** for domain modelling (results/errors/states).
- **No `var`** — always `val`. **No `!!`** without a comment explaining why it's safe.
- `Result<T>` or sealed classes instead of exceptions for expected failure cases.
- `ktlint` before every commit (jlleitschuh Gradle plugin).
- Group `io.github.unityinflow`; Maven Central via Sonatype (publishToMavenLocal until v0.0.1).
- **Test coverage >80%** on core logic before release. No secrets committed.
- CI: `runs-on: [arc-runner-unityinflow]` (never `ubuntu-latest`); must run `spec-ci-plugin` to lint CLAUDE.md.
- **Do not build the frontend** in this phase — backend/contract first.

## Summary

This phase produces two artifacts and no runtime infrastructure: (1) `docs/agent-span-convention.md` — the versioned, public attribute contract KORE and any OTel emitter will be built against; and (2) a pure-Kotlin **extractor** that turns a raw OTLP span's attribute list into a typed, immutable domain object (`AgentSpan`-style data class with nullable fields). There are no receivers, no ClickHouse, no HTTP, no frontend here — those are Phases 2–5. The entire phase is markdown + pure functions + tests, which makes it the highest test-ROI surface in the project and trivially >80%-coverable.

The load-bearing external fact is the **OpenTelemetry GenAI semantic conventions**. These moved out of the main `opentelemetry.io/docs/specs/semconv` tree into the dedicated `open-telemetry/semantic-conventions-genai` repository (the old doc URLs now only show a "moved" notice — do not cite them). Every `gen_ai.*` attribute is currently **Development** stability, and `gen_ai.operation.name` is an enum that already standardizes `invoke_agent`, `execute_tool`, `create_agent`, `chat`, `plan`, `embeddings`, etc. The convention agent-tracer publishes is therefore a **superset**: read the standard `gen_ai.*` for tokens/model/agent/operation, and add exactly three semantic gaps OTel does **not** define — cost (`unityinflow.cost.usd`), context saturation (`unityinflow.context.saturation_pct`), and the ecosystem "skill" concept (`unityinflow.skill.name`).

The single most important type fact for CONV-03: `gen_ai.input.messages` / `gen_ai.output.messages` are declared type **`any`** (structured JSON), marked **`Opt-In`**, and the spec explicitly says that when recorded *on a span* they MAY be a **JSON string** if structured form isn't supported. OTLP attribute values cannot natively carry arbitrary nested JSON in a way the typed columns need, so in practice these land as **string** OTLP attributes holding JSON — which is exactly why the convention only needs to layer size/truncation rules (256 KB cap, `unityinflow.payload.truncated`) on top, per D-13/D-14, and define no custom payload-content keys.

**Primary recommendation:** Publish `docs/agent-span-convention.md` v1.0 as a gen_ai.* superset (read standard, extend with cost/saturation/skill only), with a per-attribute OTLP-value-type column. Vendor every `gen_ai.*` and `unityinflow.*` key string into one Kotlin object in the `agent-semconv` module (the only place a future gen_ai rename touches). Build the extractor as a pure, priority-ordered (`unityinflow.*` wins) function returning an immutable data class of nullable typed fields, and test precedence + clamping + payload-cap behaviour exhaustively.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Published attribute convention (markdown contract) | Documentation / Contract (`docs/`) | — | It is a human-and-machine-readable spec, not code; it is the ecosystem's public API surface that KORE adopts |
| Typed attribute key constants (`agent-semconv`) | Domain / Library (pure Kotlin, no I/O) | — | Shared artifact consumed by both the extractor here and by KORE as an emitter dependency; must have zero heavy transitive deps |
| Attribute extraction (OTLP attrs → typed fields) | Domain logic (pure function, no I/O) | — | Pure transformation; the highest-value unit-test surface; no receiver/storage concerns belong here |
| Domain model (`AgentSpan` / run / outcome) | Domain (immutable data classes, sealed enums) | — | Feeds Phase 2's ClickHouse column design; OTLP value types per attribute gate the schema |
| OTLP wire parsing (proto → attr list) | Ingestion (Phase 3) — **NOT this phase** | — | This phase consumes an already-parsed attribute list; it must not depend on the OTLP transport |

**Tier boundary that must hold:** the extractor and domain model must accept a plain Kotlin representation of span attributes (e.g. `List<KeyValue>` from the OTLP proto, or an even simpler `Map`/typed wrapper) — they must NOT reach into Ktor, gRPC, or ClickHouse. Keeping `convention/` and `domain/` free of infra imports is what lets KORE depend on `agent-semconv` without dragging in a trace backend, and lets Phase 2 reuse the typed model directly as the column contract.

## Standard Stack

This phase **installs no new external runtime packages.** It produces the project's own first Kotlin modules. The only external library it *references* is the OTLP proto types already pinned in `.planning/research/STACK.md` (the extractor consumes the attribute list that the Phase-3 receiver will parse — design against the proto's `KeyValue`/`AnyValue` shape but do not add the receiver here).

### Core (versions verified in STACK.md 2026-06-12 — do NOT re-verify)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin | 2.4.0 | Language for `agent-semconv` + extractor + domain model | [CITED: .planning/research/STACK.md] Ecosystem constraint; latest stable |
| Gradle (Kotlin DSL) | 9.5.1 | Build, version catalog, module layout | [CITED: STACK.md] Ecosystem rule |
| io.opentelemetry.proto:opentelemetry-proto | 1.10.0-alpha | Source of `KeyValue`/`AnyValue` types the extractor maps from | [CITED: STACK.md] Already chosen; the extractor's input shape is its `Span.attributes` (`repeated KeyValue`). The `-alpha` is a permanent publishing convention; OTLP v1 trace proto is stable |
| JUnit Jupiter | 5.14.4 | Test runner | [CITED: STACK.md] Ecosystem JUnit 5 constraint |
| Kotest assertions (`kotest-assertions-core`) | 6.1.11 | Matchers for extractor/precedence/clamp tests | [CITED: STACK.md] |
| MockK | 1.14.11 | Only if a collaborator needs faking (extractor is pure — likely unused this phase) | [CITED: STACK.md] |
| kotlin-logging-jvm + logback-classic | 8.0.4 / 1.5.34 | Logging facade (minimal use this phase) | [CITED: STACK.md] |

### Supporting / decisions
| Concern | Recommendation | Note |
|---------|---------------|------|
| `agent-semconv` dependencies | **Zero or near-zero** transitive deps (ideally just Kotlin stdlib) | KORE will depend on it as an emitter; it must not pull a trace backend. Keep the OTLP-proto dependency in the *extractor* module, not in the published constants module |
| gen_ai.* key source | **Vendor the key strings** into a Kotlin object | See Open Questions Q1 — there is no stable, lightweight Kotlin `gen_ai.*` constants artifact worth depending on; the keys are Development-stability and churn, so isolating them as your own constants is the documented mitigation |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Vendoring `gen_ai.*` constants | `io.opentelemetry.semconv:opentelemetry-semconv-incubating` (Java constants jar) | [ASSUMED] An incubating Java semconv jar exists, but (a) it tracks main-repo semconv, and the GenAI conventions have *moved to a separate repo* and are Development-stability/churning, so the jar may lag or omit the newest `gen_ai.input.messages` shape; (b) it adds a transitive dep onto the lightweight `agent-semconv` artifact KORE consumes. Vendoring 2 dozen string constants is cheaper and isolates churn to one file (matches CLAUDE.md "isolate every key string" guidance). Confirm the jar's coverage during planning if you want to reconsider — flagged as Q1 |
| Single Gradle module | Two-module split: `agent-semconv` (published, pure) + extractor/domain in the app module | The published-constants surface KORE depends on should not carry the extractor's OTLP-proto dependency. A thin `agent-semconv` module + the extractor living in the backend module is the clean split. (Claude's discretion per D-03/CONTEXT.) |

**Installation:** No `npm install` / `pip install` / new Maven coordinate to add beyond what STACK.md already lists. Work is: create Gradle modules, add the already-verified test deps, write Kotlin + markdown.

## Package Legitimacy Audit

> This phase installs **no new external packages.** All libraries referenced (Kotlin, Gradle, opentelemetry-proto, JUnit, Kotest, MockK, logback) were verified against Maven Central on 2026-06-12 and are documented in `.planning/research/STACK.md`. No registry re-verification performed this session because no new coordinate is introduced.

| Package | Registry | Verdict | Disposition |
|---------|----------|---------|-------------|
| (none new) | — | — | No external install in Phase 1 |

**Packages removed due to [SLOP] verdict:** none
**Packages flagged as suspicious [SUS]:** none

*If planning later decides to depend on an upstream `opentelemetry-semconv` constants jar (Q1) instead of vendoring, that single coordinate MUST be run through `gsd-tools query package-legitimacy check --ecosystem npm/... ` — note: it is a Maven artifact, so verify via Maven Central / its POM — before adoption, and gated behind a `checkpoint:human-verify` task.*

## gen_ai.* Attribute Reference (the standard baseline to read)

All attributes below are **`Development`** stability in the OpenTelemetry GenAI semconv. [VERIFIED: github.com/open-telemetry/semantic-conventions-genai registry, fetched 2026-06-13]

| Attribute | Type | Requirement | agent-tracer use |
|-----------|------|-------------|------------------|
| `gen_ai.operation.name` | string (enum) | Required on GenAI spans | Drives node classification (plan/LLM-call/tool/subagent) |
| `gen_ai.provider.name` | string (enum) | Recommended | Provider dimension (replaces older `gen_ai.system`) |
| `gen_ai.request.model` | string | Recommended | Model dimension (requested) |
| `gen_ai.response.model` | string | Recommended | Model dimension (actual; prefer when present) |
| `gen_ai.usage.input_tokens` | **int** | Recommended | Input token count — **the only token source** (D-06) |
| `gen_ai.usage.output_tokens` | **int** | Recommended | Output token count — **the only token source** (D-06) |
| `gen_ai.agent.name` | string | Conditionally required on agent spans | Agent identity (D-10, primary) |
| `gen_ai.agent.id` | string | Recommended | Stable provider-assigned agent id |
| `gen_ai.tool.name` | string | Conditionally required on tool spans | Tool-node label; secondary skill source for tool nodes |
| `gen_ai.tool.call.id` | string | Recommended | Tool call correlation |
| `gen_ai.conversation.id` | string | Recommended | Session/thread id |
| `gen_ai.response.finish_reasons` | **string[]** | Recommended | Stop reasons (array) |
| `gen_ai.input.messages` | **any** (JSON; string on spans) | **Opt-In** | Prompt payload (replay) — see Payload Handling |
| `gen_ai.output.messages` | **any** (JSON; string on spans) | **Opt-In** | Completion payload (replay) — see Payload Handling |
| `gen_ai.system_instructions` | **any** (JSON; string on spans) | **Opt-In** | System prompt payload (replay) |

**`gen_ai.operation.name` enum values** [VERIFIED: genai registry 2026-06-13]:
`chat`, `create_agent`, `create_memory`, `create_memory_store`, `delete_memory`, `delete_memory_store`, `embeddings`, `execute_tool`, `generate_content`, `invoke_agent`, `invoke_workflow`, `plan`, `retrieval`, `search_memory`, `text_completion`, `update_memory`, `upsert_memory`.

The four that matter for v1.0 node classification: **`invoke_agent`** (agent run / subagent spawn), **`execute_tool`** (tool node), **`chat` / `generate_content` / `text_completion`** (LLM-call node), **`plan`** (plan-step node). `create_agent` is setup, not a run step.

**Span naming conventions** [VERIFIED: gen-ai-agent-spans.md 2026-06-13] — the extractor should NOT rely on span *name* for classification (names are advisory), but the doc should document them so emitters align:
- `create_agent {gen_ai.agent.name}` · `invoke_agent {gen_ai.agent.name}` (falls back to bare `invoke_agent` when name absent) · `plan {gen_ai.agent.name}` · `invoke_workflow {gen_ai.workflow.name}`.
- Per spec, instrumentations that emit `invoke_agent` SHOULD NOT also emit `invoke_workflow` (avoid double-counting). Ignore `invoke_workflow` for v1.0 run identity.

**`gen_ai.provider.name` well-known values** (string enum, open): `anthropic`, `aws.bedrock`, `azure.ai.inference`, `azure.ai.openai`, `cohere`, `deepseek`, `gcp.gemini`, `gcp.gen_ai`, `gcp.vertex_ai`, `groq`, `ibm.watsonx.ai`, `mistral_ai`, `moonshot_ai`, `openai`, `perplexity`, `x_ai`. [VERIFIED: genai registry 2026-06-13]

## Convention Document Contents (`docs/agent-span-convention.md` v1.0)

The published doc must contain (CONV-01 + CONV-03):

1. **Versioning & status** — semver 1.0; emitters stamp `unityinflow.schema_version` (string) (D-02). State that it is a **superset of OTel GenAI semconv (Development stability)** and pin the upstream semconv version range targeted.
2. **A single attribute table** with columns: `Attribute key | OTLP value type | Required/Recommended/Opt-In | Units / scale | Notes`. This table IS the contract; CONV-03 demands the OTLP value type column.
3. **The three `unityinflow.*` extensions** OTel does not provide, plus the small reserved set (D-04, D-05, D-07):

| Attribute | OTLP value type | Scale / units | Notes |
|-----------|-----------------|---------------|-------|
| `unityinflow.skill.name` | string | — | Ecosystem "skill" concept; namespaced because OTel reserves bare names |
| `unityinflow.skill.version` | string | — | D-07 |
| `unityinflow.context.saturation_pct` | **double** | **0–100** percent | D-04; extractor **clamps** out-of-range to [0,100] |
| `unityinflow.cost.usd` | **double** | USD | D-05; non-USD emitters convert before emitting; no currency code, no micro-USD int |
| `unityinflow.plan.step` | **int** | — | D-07 ordinal plan step |
| `unityinflow.outcome` | string (enum) | `success\|failure\|partial\|cancelled` | D-09 override of OTel status |
| `unityinflow.schema_version` | string | semver | D-02 |
| `unityinflow.payload.truncated` | **boolean** | — | D-13; set by the **receiver** (Phase 3) when a payload field exceeds 256 KB |
| `unityinflow.payload.original_size_bytes` | **int** | bytes | D-13; original size recorded at truncation time |

4. **Tokens come only from `gen_ai.usage.*`** — the doc must explicitly state there is NO `unityinflow.*` token attribute (D-06).
5. **Run identity & outcome semantics** (D-08/D-09/D-10): trace = run; root span = entry point; outcome = root OTel status unless `unityinflow.outcome` overrides; agent identity = `gen_ai.agent.name` else resource `service.name`.
6. **Payload handling rules** (D-13/D-14) — see next section.
7. **Extraction precedence statement** (D-11): when `unityinflow.*` and `gen_ai.*` disagree on the same logical field, `unityinflow.*` wins.

## OTLP Value-Type Mapping (CONV-03 core)

OTLP carries each attribute as a `KeyValue { key, AnyValue value }`. `AnyValue` is a `oneof` with these variants: `string_value`, `bool_value`, `int_value` (int64), `double_value`, `array_value` (`ArrayValue`), `kvlist_value` (`KeyValueList`), `bytes_value`. [ASSUMED — OTLP common proto type system from training knowledge; the proto jar is pinned in STACK.md, confirm field names against `opentelemetry-proto` `common/v1/common.proto` during planning if exact spelling matters.]

The convention's per-attribute "OTLP value type" column maps as:

| Convention type | OTLP `AnyValue` variant | Kotlin typed field | Extractor note |
|-----------------|-------------------------|--------------------|----------------|
| string | `string_value` | `String?` | direct |
| int (tokens, plan.step, original_size) | `int_value` (int64) | `Long?` (or `Int?` for plan.step) | reject/ignore if emitter sent a `string_value` "1234"; do NOT lexically parse silently — but consider a tolerant parse for resilience (flag in Open Questions Q2) |
| double (cost.usd, saturation_pct) | `double_value` | `Double?` | saturation: **clamp** to [0,100] after reading (D-04) |
| boolean (payload.truncated) | `bool_value` | `Boolean?` | direct |
| string[] (finish_reasons) | `array_value` of `string_value` | `List<String>?` | direct map |
| any/JSON (gen_ai.*.messages) | `string_value` holding JSON (per spec: on spans MAY be JSON string) | `String?` (raw JSON payload) | do NOT parse the JSON in this phase; carry verbatim, apply size rules |

**Critical pitfall this addresses (from PITFALLS.md Pitfall 3):** OTLP attribute values are typed, and naive extractors stringify everything. The extractor MUST preserve OTLP value types: a token count arrives as `int_value` and must land in a `Long`/`Int` field, not `"1234"`. This is what makes Phase 2's typed ClickHouse columns possible — the convention's value-type column is the schema contract.

## Payload Handling Rules (CONV-03 + D-13/D-14)

Verified facts driving the rules [VERIFIED: gen-ai-spans.md 2026-06-13]:
- `gen_ai.input.messages`, `gen_ai.output.messages`, `gen_ai.system_instructions` are type **`any`** and **`Opt-In`** (content capture is off by default in OTel instrumentations).
- Spec note 18/19: *"When the attribute is recorded on events, it MUST be recorded in structured form. When recorded on spans, it MAY be recorded as a JSON string if structured form is not supported."* → On a span carried over OTLP, expect these as **string attributes holding JSON**.
- Spec warns these *"likely contain sensitive information including user/PII data"* and that instrumentations MAY filter/truncate.

Therefore the convention adds (it does NOT invent new content keys — D-14):
- **Source keys:** prompt = `gen_ai.input.messages`, completion = `gen_ai.output.messages`, system = `gen_ai.system_instructions`. Standard OTel keys only.
- **Cap:** 256 KB per payload field (D-13). Over-cap payloads are truncated **by the receiver in Phase 3**, not by the extractor — but the convention doc and domain model define the contract now.
- **Truncation signalling:** `unityinflow.payload.truncated=true` (boolean) and `unityinflow.payload.original_size_bytes` (int) record that truncation happened and the original size. Replay surfaces this honestly.
- **PII note:** the doc should carry the OTel sensitive-data warning forward (security note below), and state that payloads are opt-in/redactable at the emitter.

## Architecture Patterns

### System Architecture Diagram (Phase 1 data flow only)

```
  OTLP span (already parsed — Phase 3 provides this)
  ┌─────────────────────────────────────────────────┐
  │ Span {                                           │
  │   name, status, span_id, parent_span_id,         │
  │   attributes: List<KeyValue{key, AnyValue}>      │  ← input shape
  │ } + ResourceAttributes (service.name)            │
  └───────────────────────┬─────────────────────────┘
                          │
                          ▼
        ┌───────────────────────────────────────┐
        │ AgentSpanExtractor.extract(span,       │   pure function, no I/O
        │   resourceAttrs): AgentSpan            │
        │                                        │
        │  for each logical field:               │
        │    1. read unityinflow.* key  ───┐     │   D-11 precedence:
        │    2. else read gen_ai.* key     ├──►   │   unityinflow.* wins
        │    3. else null                  │     │
        │    apply type coercion + clamp ──┘     │   D-04 clamp, D-12 nullable
        └───────────────────┬───────────────────┘
                            │
                            ▼
        ┌───────────────────────────────────────┐
        │ AgentSpan (immutable data class)       │   typed nullable fields:
        │  agentName, model, skillName,          │   String?
        │  inputTokens, outputTokens (Long?),    │
        │  costUsd, saturationPct (Double?),     │   feeds Phase 2 column design
        │  operation (enum), outcome (sealed),   │
        │  inputPayload, outputPayload (String?),│
        │  payloadTruncated (Boolean?), …        │
        └───────────────────────────────────────┘
```

A reader traces: parsed OTLP span attributes → priority-ordered field lookup (unityinflow.* over gen_ai.*) with type coercion/clamping → immutable typed `AgentSpan`. No storage, no network.

### Recommended Project Structure (Claude's discretion — D-03)
```
agent-tracer/
├── settings.gradle.kts          # includes :agent-semconv and :backend
├── gradle/libs.versions.toml    # version catalog (Kotlin 2.4.0, JUnit 5.14.4, Kotest 6.1.11, …)
├── agent-semconv/               # PUBLISHED module — pure key constants, minimal deps
│   ├── build.gradle.kts         # publishToMavenLocal until v0.0.1; group io.github.unityinflow
│   └── src/main/kotlin/io/github/unityinflow/agentsemconv/
│       ├── GenAiAttributes.kt   # vendored gen_ai.* key strings (the ONLY place they live)
│       ├── UnityInFlowAttributes.kt  # unityinflow.* key strings
│       └── GenAiOperation.kt    # operation.name enum values
├── backend/                     # this phase: domain + convention/extractor only
│   └── src/main/kotlin/io/github/unityinflow/agenttracer/
│       ├── domain/              # AgentSpan, AgentOutcome (sealed), AgentOperation (enum)
│       └── convention/          # AgentSpanExtractor (pure), value-type coercion helpers
│   └── src/test/kotlin/…        # extractor precedence/clamp/payload/type tests
├── docs/agent-span-convention.md   # THE published contract (D-01)
└── README.md                    # links to the convention doc
```

### Pattern 1: gen_ai.* superset, not a parallel vocabulary
**What:** Read standard `gen_ai.*` for everything OTel defines (tokens, model, agent, operation, conversation, messages); add `unityinflow.*` only for the three real gaps (cost, saturation, skill) + the small reserved set.
**When:** Always — it is the whole CONV-02 thesis ("any gen_ai.*-emitting source renders usefully with zero changes").
**Why:** anything emitting standard gen_ai.* (LangChain/OpenAI/Anthropic instrumentations, vendor SDKs) renders in agent-tracer unchanged; KORE adds the extras.

### Pattern 2: Priority-ordered extraction (`unityinflow.*` wins)
**What:** For each logical field, look up the `unityinflow.*` key first, then the `gen_ai.*` standard key, then resource-level fallback (agent identity → `service.name`), else `null`.
**When:** Always (D-11). Precedence is a roadmap success criterion and MUST be test-verified.

### Pattern 3: Isolate every attribute key string in one place
**What:** No raw `"gen_ai.usage.input_tokens"` literals scattered through code — all keys live in `agent-semconv` constants.
**Why:** gen_ai semconv is Development-stability and has renamed attributes before (e.g. `gen_ai.system` → `gen_ai.provider.name`). A rename must be a one-file change. [CITED: ARCHITECTURE.md Pattern 4]

### Anti-Patterns to Avoid
- **Stringifying typed OTLP values:** turning an `int_value` token count into `"1234"` — breaks Phase 2 typed columns and numeric aggregation (PITFALLS.md Pitfall 3).
- **Classifying nodes by span *name*:** names are advisory (`invoke_agent {name}`); classify by `gen_ai.operation.name`. 
- **Parsing message JSON in this phase:** carry payloads verbatim as opaque strings; structure-parsing is a replay/UI concern, not extraction.
- **Inventing `unityinflow.payload.*` content keys:** D-14 forbids it — reuse `gen_ai.*.messages`, add only size/truncation metadata keys.
- **Non-null sentinel defaults (`cost = 0.0`):** D-12 — absence must be `null`; `0.0` is a real cost that would corrupt aggregates.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| OTLP attribute value model | A custom key/value/type union | The `AnyValue`/`KeyValue` types from `opentelemetry-proto` (already pinned) | The wire format is fixed; design the extractor against the proto types so Phase 3 plugs in directly |
| gen_ai vocabulary | A bespoke token/model/agent naming scheme | OTel GenAI semconv `gen_ai.*` (Development) | CONV-02 requires reading the standard so generic emitters work unchanged |
| Message/payload content schema | Custom prompt/completion attributes | `gen_ai.input.messages` / `gen_ai.output.messages` (type `any`, opt-in) | D-14; reusing the standard means LangChain/OpenAI instrumentations' payloads already fit |
| Result/error modelling | Exceptions for expected "attribute missing/invalid" cases | Nullable fields + (if needed) a sealed `Result` | CLAUDE.md: `Result<T>`/sealed over exceptions; D-12 nullable absence |

**Key insight:** This phase's value is *defining the contract precisely*, not building machinery. Almost every "thing" already has an OTel standard — the only genuinely new vocabulary is cost + saturation + skill.

## Runtime State Inventory

> Greenfield phase — no rename/refactor/migration. **Omitted** (no stored data, services, OS state, secrets, or build artifacts pre-exist; the repo has no source code yet per CONTEXT.md "greenfield repo").

## Common Pitfalls

### Pitfall 1: Burying typed agent fields as strings (the precedent for Phase 2)
**What goes wrong:** extractor stringifies OTLP values; cost/tokens become `String`, killing numeric aggregation downstream.
**Why:** OTLP delivers a generic key/value list; stringify is the path of least resistance.
**How to avoid:** preserve OTLP value type per the value-type mapping table; land int in `Long`, double in `Double`, etc. The convention's value-type column is the enforcement spec.
**Warning signs:** the domain model has `cost: String`; tests assert `"0.05"` rather than `0.05`.
[CITED: .planning/research/PITFALLS.md Pitfall 3]

### Pitfall 2: Treating the gen_ai.* vocabulary as stable
**What goes wrong:** scatter raw key literals, then a Development-stability rename (`gen_ai.system` → `gen_ai.provider.name`) breaks extraction in many files.
**How to avoid:** vendor all keys in `agent-semconv`; document the targeted upstream semconv version in the convention doc (D-02 schema_version helps emitters; pinning helps you).
**Warning signs:** `"gen_ai."` string literals outside `agent-semconv`.
[VERIFIED: all gen_ai.* attrs are Development stability, genai registry 2026-06-13]

### Pitfall 3: Precedence and clamping left untested
**What goes wrong:** when both `unityinflow.cost.usd` and a hypothetical generic value exist, or saturation = 137, behaviour is undefined; D-11/D-04 become aspirational.
**How to avoid:** dedicated tests: (a) unityinflow.* overrides gen_ai.* on conflict; (b) saturation clamps to [0,100]; (c) missing field → null; (d) gen_ai-only span still extracts agent/tokens/model. These four map directly to the phase's Success Criteria 3 & 4.
**Warning signs:** no test named for precedence; success-criterion 4 unverifiable.

### Pitfall 4: Opt-in payloads assumed present
**What goes wrong:** extractor/tests assume `gen_ai.input.messages` is always there; but it is **Opt-In** and off by default, so most real spans won't carry it.
**How to avoid:** payload fields are nullable (D-12); the gen_ai-only golden span test should NOT require payloads; the payload-cap/truncation contract is documented but exercised mainly in Phase 3.

## Code Examples

> No verbatim upstream code example exists for this bespoke extractor (it is project-specific). The pattern below is illustrative, grounded in the verified attribute names/types above and CLAUDE.md Kotlin rules. Treat as a design sketch, not copy-paste.

### Vendored keys (the single source of truth)
```kotlin
// agent-semconv/src/main/kotlin/.../GenAiAttributes.kt
object GenAiAttributes {
    const val OPERATION_NAME = "gen_ai.operation.name"
    const val PROVIDER_NAME = "gen_ai.provider.name"
    const val REQUEST_MODEL = "gen_ai.request.model"
    const val RESPONSE_MODEL = "gen_ai.response.model"
    const val USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens"   // int
    const val USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens" // int
    const val AGENT_NAME = "gen_ai.agent.name"
    const val TOOL_NAME = "gen_ai.tool.name"
    const val INPUT_MESSAGES = "gen_ai.input.messages"   // any / JSON string on spans, opt-in
    const val OUTPUT_MESSAGES = "gen_ai.output.messages" // any / JSON string on spans, opt-in
}

object UnityInFlowAttributes {
    const val SKILL_NAME = "unityinflow.skill.name"
    const val SKILL_VERSION = "unityinflow.skill.version"
    const val CONTEXT_SATURATION_PCT = "unityinflow.context.saturation_pct" // double 0-100
    const val COST_USD = "unityinflow.cost.usd"                              // double
    const val PLAN_STEP = "unityinflow.plan.step"                           // int
    const val OUTCOME = "unityinflow.outcome"
    const val SCHEMA_VERSION = "unityinflow.schema_version"
    const val PAYLOAD_TRUNCATED = "unityinflow.payload.truncated"
}
```

### Priority-ordered extraction with type preservation + clamp (D-11, D-04, D-12)
```kotlin
// Pure function; `attrs` is a typed view over OTLP KeyValue list.
fun extract(attrs: AttrView, resource: AttrView): AgentSpan = AgentSpan(
    agentName = attrs.string(GenAiAttributes.AGENT_NAME)
        ?: resource.string("service.name"),                      // D-10 fallback
    model = attrs.string(GenAiAttributes.RESPONSE_MODEL)
        ?: attrs.string(GenAiAttributes.REQUEST_MODEL),
    skillName = attrs.string(UnityInFlowAttributes.SKILL_NAME)   // unityinflow.* first (D-11)
        ?: attrs.string(GenAiAttributes.TOOL_NAME),              // generic fallback for tool nodes
    inputTokens = attrs.long(GenAiAttributes.USAGE_INPUT_TOKENS),    // Long? — D-06, type preserved
    outputTokens = attrs.long(GenAiAttributes.USAGE_OUTPUT_TOKENS),
    costUsd = attrs.double(UnityInFlowAttributes.COST_USD),         // Double? — D-12 null if absent
    saturationPct = attrs.double(UnityInFlowAttributes.CONTEXT_SATURATION_PCT)
        ?.coerceIn(0.0, 100.0),                                     // D-04 clamp
    operation = attrs.string(GenAiAttributes.OPERATION_NAME)
        ?.let { AgentOperation.fromOtel(it) },
    inputPayload = attrs.string(GenAiAttributes.INPUT_MESSAGES),    // verbatim JSON, opt-in (nullable)
    outputPayload = attrs.string(GenAiAttributes.OUTPUT_MESSAGES),
)
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| GenAI semconv lived under `opentelemetry.io/docs/specs/semconv/gen-ai/*` | Moved to dedicated repo `open-telemetry/semantic-conventions-genai` | Recent (old doc pages now show a "moved" notice) | **Cite the new repo, not the old URLs.** Old `opentelemetry.io/.../gen-ai-spans/` pages return only a redirect notice |
| `gen_ai.system` | `gen_ai.provider.name` | Earlier semconv churn | Use `gen_ai.provider.name`; treat the rename as proof that keys must be isolated |
| Message content as **log events** (`gen_ai.*.message` events) | Message content as **span attributes** `gen_ai.input.messages` / `gen_ai.output.messages` (type `any`, opt-in; JSON string on spans) | Current genai semconv | Read payloads from span attributes; expect JSON strings; honour the opt-in (often absent) |

**Deprecated/outdated:**
- `gen_ai.system` attribute → replaced by `gen_ai.provider.name`.
- Old `opentelemetry.io` gen-ai semconv doc URLs → unmaintained; use the genai repo.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | OTLP `AnyValue` variants are `string/bool/int(64)/double/array/kvlist/bytes` | OTLP Value-Type Mapping | LOW — exact field names should be confirmed against the pinned `opentelemetry-proto` `common/v1/common.proto`; the variant *set* is stable OTLP v1 |
| A2 | An upstream `opentelemetry-semconv` Java constants jar exists but lags the moved GenAI conventions | Standard Stack / Alternatives | LOW — affects only the vendor-vs-depend decision (Q1); recommendation (vendor) is robust either way |
| A3 | gen_ai message payloads arrive on OTLP spans as `string_value` holding JSON | Payload Handling / Value-Type Mapping | MEDIUM — if an emitter sends them as `kvlist`/`array` AnyValue instead, the extractor's payload reader must also accept structured AnyValue and serialize it; design the payload reader to tolerate both. Spec says spans MAY use JSON string, which is the dominant case |

## Open Questions (RESOLVED)

1. **Vendor gen_ai.* keys vs depend on an upstream semconv constants artifact?**
   - What we know: keys are Development-stability and the conventions moved repos; CLAUDE.md/ARCHITECTURE.md both say isolate keys in one place.
   - What's unclear: whether a maintained Kotlin/Java `gen_ai.*` constants jar covers the newest `gen_ai.input.messages` shape.
   - RESOLVED: vendor the gen_ai.* key constants (~2 dozen string constants in `agent-semconv`). Cheaper, zero transitive deps on the KORE-facing artifact, isolates churn. Matches D-03 and plan 01. Re-evaluate only if a maintained jar with full coverage is found during planning (then gate via package-legitimacy check).

2. **Strict vs tolerant type coercion when an emitter sends the wrong OTLP type** (e.g. tokens as `string_value` "1234").
   - What we know: spec says tokens are `int`; the receiver "any gen_ai source renders usefully" goal argues for resilience.
   - RESOLVED: strict coercion — a wrong AnyValue variant yields null; no tolerant/lexical fallback parse. The extractor reads only the correctly-typed AnyValue variant; any other variant (or absence) returns null (D-12). Matches plan 02 Task 1.

3. **Does the domain model already need a `node kind` enum in Phase 1**, or only `operation`?
   - RESOLVED: only AgentOperation enum in Phase 1; defer DAG node-kind to the query phase. Model `gen_ai.operation.name` as an `AgentOperation` enum now (it is the contract); leave DAG node-kind classification (which combines operation + isAgentRoot) to the query phase. Keeps Phase 1 to extraction only. Matches plan 02.

## Environment Availability

> **Skipped.** This phase is pure code + markdown with no external runtime dependencies (no DB, no network services, no CLI tools beyond the JDK 21 / Gradle toolchain already required by the ecosystem). Per Step 2.6 skip condition.

## Validation Architecture

> `workflow.nyquist_validation` is `true` in `.planning/config.json` — section included.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit Jupiter 5.14.4 (runner) + Kotest assertions-core 6.1.11 (matchers) |
| Config file | none yet — created in Wave 0 (`backend/build.gradle.kts` test block, `useJUnitPlatform()`) |
| Quick run command | `./gradlew :backend:test --tests "*Extractor*"` |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CONV-01 | Convention doc defines all v1.0 attributes with type column | doc/structural (optional lint) + constants exist | `./gradlew :agent-semconv:test` | ❌ Wave 0 |
| CONV-02 | gen_ai-only span → typed agent fields (tokens/model/agent/operation), zero unityinflow.* needed | unit | `./gradlew :backend:test --tests "*GenAiOnly*"` | ❌ Wave 0 |
| CONV-02 | `invoke_agent`/`execute_tool` operation parsed into enum | unit | `./gradlew :backend:test --tests "*Operation*"` | ❌ Wave 0 |
| CONV-03 | OTLP value type preserved per attribute (int→Long, double→Double, bool→Boolean) | unit | `./gradlew :backend:test --tests "*ValueType*"` | ❌ Wave 0 |
| CONV-03 | Payload cap/truncation contract represented in model (`payloadTruncated`, original size) | unit | `./gradlew :backend:test --tests "*Payload*"` | ❌ Wave 0 |
| (SC-4) | **Precedence:** unityinflow.* overrides gen_ai.* on conflict | unit | `./gradlew :backend:test --tests "*Precedence*"` | ❌ Wave 0 |
| (D-04) | saturation_pct clamps to [0,100] | unit | `./gradlew :backend:test --tests "*Clamp*"` | ❌ Wave 0 |
| (D-12) | missing field → null (no sentinel) | unit | `./gradlew :backend:test --tests "*Nullable*"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :backend:test --tests "*Extractor*"` (sub-second, pure functions)
- **Per wave merge:** `./gradlew test`
- **Phase gate:** Full suite green + `ktlintCheck` clean before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `settings.gradle.kts` + `gradle/libs.versions.toml` (version catalog) — no build exists yet (greenfield)
- [ ] `agent-semconv/build.gradle.kts` and `backend/build.gradle.kts` with `useJUnitPlatform()`, Kotest + JUnit 5 deps
- [ ] Test fixtures: a **golden gen_ai-only span** (no unityinflow.*) and a **full unityinflow.* span** as Kotlin builders or JSON, reused across tests
- [ ] No framework install needed beyond declaring the already-verified test deps

## Security Domain

> `security_enforcement: true`, `security_asvs_level: 1`. This phase produces a markdown contract + pure extractor; it has no auth, no network, no storage. The relevant security content is **what the convention must say about payloads**, since it governs what downstream phases persist.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | No auth surface in Phase 1 |
| V3 Session Management | no | — |
| V4 Access Control | no | — |
| V5 Input Validation | **yes** | Extractor validates/coerces OTLP value types defensively; clamps saturation; tolerates missing/mistyped attributes without throwing (sealed/nullable, not exceptions) |
| V6 Cryptography | no | No secrets handled this phase |
| V7/V9 (Logging/Data Protection) | **yes (advisory)** | The convention doc MUST carry OTel's sensitive-data warning: `gen_ai.input.messages`/`output.messages` "likely contain sensitive information including user/PII data." Document that payloads are opt-in and SHOULD be redactable/truncatable at the emitter; the 256 KB cap + future TTL are mitigations |

### Known Threat Patterns
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| PII/secrets in prompt payloads persisted long-term | Information Disclosure | Convention documents the risk; payloads opt-in (off by default per OTel); cap + truncation; redaction guidance for emitters; downstream TTL (Phase 2) |
| Malformed/oversized attribute crashes the extractor | Denial of Service | Pure extractor must never throw on bad input — nullable/sealed handling (V5); size cap defined in contract (enforced Phase 3) |
| Attacker-controlled attribute values (skill name, messages) later rendered in UI | Tampering (stored XSS, Phase 5) | Out of scope for extraction, but the convention should note attribute values are untrusted strings so the Phase 5 UI escapes them [CITED: PITFALLS.md Security Mistakes] |

**Note:** `security_block_on: high` — no high-severity security control is *implemented* in this phase; the obligation is documentary (carry the PII warning into the contract) plus defensive extraction. No blocking security task expected.

## Sources

### Primary (HIGH confidence)
- `open-telemetry/semantic-conventions-genai` repo — `docs/registry/attributes/gen-ai.md` (attribute names, types, stability), `docs/gen-ai/gen-ai-spans.md` (message attrs type `any`/`Opt-In`, "JSON string on spans" rule, sensitive-data warnings), `docs/gen-ai/gen-ai-agent-spans.md` (operation enum, span naming) — fetched via `gh api` 2026-06-13
- `.planning/research/STACK.md` — verified library versions (Kotlin 2.4.0, opentelemetry-proto 1.10.0-alpha, Gradle 9.5.1, JUnit 5.14.4, Kotest 6.1.11) [fetched 2026-06-12]
- `.planning/research/ARCHITECTURE.md` — gen_ai.* superset rationale, "isolate keys in one package," Development-status warning
- `.planning/research/PITFALLS.md` — typed-column pitfall (Pitfall 3), payload pitfall (Pitfall 9), security mistakes
- `.planning/phases/01-attribute-convention-domain-model/01-CONTEXT.md` — locked decisions D-01..D-14

### Secondary (MEDIUM confidence)
- OTel GenAI message-content capture rules (opt-in, JSON, PII warning) cross-read via WebSearch + the genai repo source — corroborated

### Tertiary (LOW confidence / ASSUMED)
- OTLP `AnyValue` variant set (string/bool/int/double/array/kvlist/bytes) — training knowledge; confirm field spellings against the pinned `opentelemetry-proto` common proto during planning
- Existence/coverage of an upstream `opentelemetry-semconv` Java constants jar — vendor recommendation is robust regardless

## Metadata

**Confidence breakdown:**
- gen_ai.* attribute names/types/enum/stability: **HIGH** — verified against the live genai semconv repo registry + spans docs on 2026-06-13
- Message-content / payload rules (type `any`, opt-in, JSON-on-spans, PII): **HIGH** — quoted from `gen-ai-spans.md`
- OTLP value-type mapping detail: **MEDIUM** — variant set known; exact proto field spelling to confirm against pinned jar
- Kotlin module/extractor design: **HIGH** (follows locked decisions + CLAUDE.md), specifics are Claude's discretion per CONTEXT
- Stack versions: **HIGH** (inherited from STACK.md, not re-verified per instruction)

**Research date:** 2026-06-13
**Valid until:** ~2026-07-13 for the Kotlin/stack pins; **~2026-06-27 (2 weeks)** for the gen_ai.* attribute specifics — they are Development-stability and actively changing in a fast-moving repo, so re-check the genai registry before locking the convention doc's targeted upstream version.
