# agent-span Convention

**Version:** 1.0.0 (semver) · **Status:** Stable contract · **Schema version stamp:** `1.0`

This is the public, versioned contract that [agent-tracer](../README.md) is built
against. [KORE](https://github.com/UnityInFlow/kore) and any OpenTelemetry-compatible
source emit spans following this convention; agent-tracer ingests, stores, and renders
them as agent decision trees with token cost, context saturation, and replay.

> The machine-readable form of this contract is the published
> `io.github.unityinflow:agent-semconv` artifact: every attribute key string below
> is a constant in `GenAiAttributes` / `UnityInFlowAttributes`. Depend on the
> artifact instead of hardcoding strings.

---

## 1. Versioning & Status

- This convention is **semver'd, starting at 1.0.0** (D-02).
- Emitters **SHOULD** stamp `unityinflow.schema_version` (string, e.g. `"1.0"`) on
  spans so consumers can branch on the contract version in the future.
- This convention is a **strict superset of the OpenTelemetry GenAI semantic
  conventions** (`gen_ai.*`). It reads the standard `gen_ai.*` attributes for
  tokens, model, agent, operation, and payloads, and adds exactly three semantic
  gaps OTel does not model — **cost**, **context saturation**, and the ecosystem
  **skill** concept — plus a small reserved bookkeeping set.
- **Targeted upstream semconv:** the OpenTelemetry GenAI semantic conventions as
  published in the `open-telemetry/semantic-conventions-genai` repository
  (registry verified 2026-06-13). All `gen_ai.*` attributes are currently
  **Development** stability upstream; agent-tracer isolates every key string in
  one place so an upstream rename is a one-line change. The
  `gen_ai.system` → `gen_ai.provider.name` rename is the precedent for why the
  `unityinflow.schema_version` stamp exists.

**The bar:** a span carrying only standard `gen_ai.*` attributes — no
`unityinflow.*` at all — MUST extract into typed agent fields with zero emitter
changes. "Any OTel-compatible source renders usefully" is a documented promise,
not an implementation accident.

---

## 2. Attribute Contract Table

This single table **is the contract**. The **OTLP value type** column is the
schema source of truth for downstream typed storage (CONV-03): a `gen_ai.usage.*`
token count arrives as an OTLP `int_value` and lands in an integer column — never
a stringified `"1234"`.

OTLP carries each attribute as `KeyValue { key, AnyValue value }`, where `AnyValue`
is a `oneof` over `string_value`, `bool_value`, `int_value` (int64), `double_value`,
`array_value`, `kvlist_value`, `bytes_value`.

### Standard `gen_ai.*` attributes (read as-is)

| Attribute key | OTLP value type | Req. | Units / scale | Notes |
|---------------|-----------------|------|---------------|-------|
| `gen_ai.operation.name` | `string_value` | Required | enum | Drives node classification (see §5). Enum incl. `chat`, `plan`, `execute_tool`, `invoke_agent`, `generate_content`, `text_completion`, `embeddings`. |
| `gen_ai.provider.name` | `string_value` | Recommended | enum | Provider dimension (replaces older `gen_ai.system`). |
| `gen_ai.request.model` | `string_value` | Recommended | — | Model requested by the caller. |
| `gen_ai.response.model` | `string_value` | Recommended | — | Model that served the response; prefer when present. |
| `gen_ai.usage.input_tokens` | `int_value` (int64) | Recommended | tokens | **The only input-token source** (D-06). |
| `gen_ai.usage.output_tokens` | `int_value` (int64) | Recommended | tokens | **The only output-token source** (D-06). |
| `gen_ai.agent.name` | `string_value` | Cond. required | — | Agent identity, primary (D-10). |
| `gen_ai.agent.id` | `string_value` | Recommended | — | Stable agent identifier. |
| `gen_ai.tool.name` | `string_value` | Cond. required | — | Tool-node label. |
| `gen_ai.tool.call.id` | `string_value` | Recommended | — | Tool-call correlation id. |
| `gen_ai.conversation.id` | `string_value` | Recommended | — | Session / thread id. |
| `gen_ai.response.finish_reasons` | `array_value` of `string_value` | Recommended | — | Stop reasons (string list). |
| `gen_ai.input.messages` | `string_value` holding JSON | **Opt-In** | ≤256 KB | Prompt payload; carried verbatim. See §6. |
| `gen_ai.output.messages` | `string_value` holding JSON | **Opt-In** | ≤256 KB | Completion payload; carried verbatim. See §6. |
| `gen_ai.system_instructions` | `string_value` holding JSON | **Opt-In** | ≤256 KB | System-prompt payload; carried verbatim. See §6. |

`service.name` (OTel resource attribute, `string_value`) is the agent-identity
fallback (D-10) — see §5.

### `unityinflow.*` extensions and reserved set

| Attribute key | OTLP value type | Req. | Units / scale | Notes |
|---------------|-----------------|------|---------------|-------|
| `unityinflow.skill.name` | `string_value` | Recommended | — | Ecosystem "skill" concept; namespaced because OTel reserves bare names (D-04). |
| `unityinflow.skill.version` | `string_value` | Opt-In | — | Version of the activated skill (D-07). |
| `unityinflow.context.saturation_pct` | `double_value` | Recommended | **0–100** percent | Context-window saturation; the extractor **clamps** out-of-range values to `[0, 100]` (D-04). |
| `unityinflow.cost.usd` | `double_value` | Recommended | USD | Monetary cost in USD; non-USD emitters convert before emitting — **no currency code, no micro-USD integer** (D-05). |
| `unityinflow.plan.step` | `int_value` (int64) | Opt-In | ordinal | Plan-step this span belongs to (D-07). |
| `unityinflow.outcome` | `string_value` | Opt-In | enum `success\|failure\|partial\|cancelled` | Overrides the OTel-status-derived outcome (D-09). |
| `unityinflow.schema_version` | `string_value` | Recommended | semver | Convention version stamp (D-02). |
| `unityinflow.payload.truncated` | `bool_value` | Set by receiver | — | `true` when a payload field exceeded the 256 KB cap (D-13). |
| `unityinflow.payload.original_size_bytes` | `int_value` (int64) | Set by receiver | bytes | Original payload size recorded at truncation time (D-13). |

---

## 3. Typed Extraction (CONV-03)

The extractor preserves OTLP value types — it never stringifies. Each convention
type maps to exactly one `AnyValue` variant and one typed field:

| Convention type | OTLP `AnyValue` variant | Typed field | Extractor rule |
|-----------------|-------------------------|-------------|----------------|
| string | `string_value` | `String?` | direct |
| int (tokens, `plan.step`, `original_size_bytes`) | `int_value` (int64) | `Long?` / `Int?` | read the int variant only; a wrong variant (e.g. `string_value` `"1234"`) yields `null` — no silent lexical parse |
| double (`cost.usd`, `saturation_pct`) | `double_value` | `Double?` | `saturation_pct` is clamped to `[0, 100]` after reading (D-04) |
| boolean (`payload.truncated`) | `bool_value` | `Boolean?` | direct |
| string[] (`finish_reasons`) | `array_value` of `string_value` | `List<String>?` | direct map |
| any / JSON (`gen_ai.*.messages`) | `string_value` holding JSON | `String?` | carried verbatim — not parsed in this contract; size rules in §6 apply |

Absence is information: every field is **nullable with no sentinel default** (D-12).
Aggregations skip nulls.

---

## 4. Tokens Come Only From `gen_ai.usage.*` (D-06)

There is **no `unityinflow.*` token attribute** and there never will be in v1.x.
Input and output token counts are read **exclusively** from
`gen_ai.usage.input_tokens` and `gen_ai.usage.output_tokens`. Cost
(`unityinflow.cost.usd`) is reported independently and is not derived from token
counts in this contract.

---

## 5. Run Identity & Outcome Semantics

- **Trace = run** (D-08). One OTel trace is one agent run; the **root span** is the
  run's entry point. There is no separate run-id attribute.
- **Outcome** (D-09) defaults to the **root span's OTel status**: `OK` → `success`,
  `ERROR` → `failure`. An optional `unityinflow.outcome`
  (`success | failure | partial | cancelled`) **overrides** the status-derived
  outcome when the emitter knows better.
- **Agent identity** (D-10) = `gen_ai.agent.name` when present, falling back to the
  OTel resource `service.name`. Generic OTel sources still get a usable agent
  dimension.
- **Node classification** uses `gen_ai.operation.name`, **not** the span name. Span
  names are advisory (e.g. `invoke_agent {gen_ai.agent.name}`); emitters SHOULD
  follow the GenAI span-naming guidance, but consumers MUST classify by operation.
  The operations that matter for v1.0: `invoke_agent` (agent run / subagent),
  `execute_tool` (tool node), `chat` / `generate_content` / `text_completion` /
  `embeddings` (LLM-call node), `plan` (plan-step node).

---

## 6. Payload Handling Rules (D-13 / D-14)

- **Source keys are the standard OTel GenAI message attributes only:** prompt =
  `gen_ai.input.messages`, completion = `gen_ai.output.messages`, system =
  `gen_ai.system_instructions`. This convention defines **no custom
  `unityinflow.payload.*` content keys** (D-14) — it layers only size and
  truncation metadata on top.
- These attributes are type `any` and **Opt-In** upstream (content capture is off
  by default). When recorded on a span over OTLP, expect them as **`string_value`
  holding JSON**.
- **Cap:** **256 KB** (262 144 bytes) per payload field (D-13).
- **Truncation is performed by the receiver** (Phase 3), not the emitter or the
  extractor. When a payload field exceeds the cap, the receiver truncates it and
  sets `unityinflow.payload.truncated = true` (boolean) and
  `unityinflow.payload.original_size_bytes` (int) to the pre-truncation size.
  Replay surfaces truncation honestly — a truncated payload is shown as truncated,
  never as if it were complete.

---

## 7. Extraction Precedence (D-11)

When `unityinflow.*` and `gen_ai.*` both supply the same logical field and the
values **disagree, `unityinflow.*` wins** — the specific ecosystem extension takes
precedence over the generic standard. This precedence is enforced and test-verified
in the extractor.

---

## Security & Sensitive Data

> **Warning (carried forward from the OpenTelemetry GenAI semconv):**
> `gen_ai.input.messages`, `gen_ai.output.messages`, and `gen_ai.system_instructions`
> **likely contain sensitive information, including user and PII data.**

- Payload capture is **Opt-In and off by default**. Emitters SHOULD make payloads
  **redactable and truncatable at the source** before they ever leave the
  instrumented process.
- The 256 KB cap and receiver-side truncation are partial mitigations; a downstream
  retention/TTL policy is a later mitigation (Phase 2 storage), not part of this
  contract.
- All attribute values are **untrusted strings**. Any rendering surface — notably
  the Phase 5 UI — MUST escape attribute values before display; treat them as
  hostile input, not trusted markup.

---

*This convention is owned and hosted by agent-tracer (Tool 09, UnityInFlow
ecosystem). Changes follow semver; breaking changes bump the major version and the
`unityinflow.schema_version` stamp.*
