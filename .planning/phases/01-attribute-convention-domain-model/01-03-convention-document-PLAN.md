---
phase: 01-attribute-convention-domain-model
plan: 03
type: execute
wave: 2
depends_on: ["01-01"]
files_modified:
  - docs/agent-span-convention.md
  - README.md
autonomous: true
requirements: [CONV-01, CONV-03]
must_haves:
  truths:
    - "A developer can read a published, versioned (semver 1.0) convention defining unityinflow.skill.name, unityinflow.context.saturation_pct, unityinflow.cost.usd alongside standard gen_ai.* token/model/agent attributes"
    - "The convention specifies the OTLP value type for every attribute in a single contract table"
    - "The convention specifies large prompt/completion payload handling rules (256 KB cap, truncation signalling) and the gen_ai.* payload source keys"
    - "README links to the convention document"
  artifacts:
    - path: "docs/agent-span-convention.md"
      provides: "The published, versioned agent span attribute contract"
      contains: "unityinflow.context.saturation_pct"
      min_lines: 60
    - path: "README.md"
      provides: "Project README linking the convention contract"
      contains: "agent-span-convention.md"
  key_links:
    - from: "README.md"
      to: "docs/agent-span-convention.md"
      via: "markdown link"
      pattern: "agent-span-convention\\.md"
---

<objective>
Publish `docs/agent-span-convention.md` v1.0 — the ecosystem's public, versioned contract that KORE and any OTel emitter will be built against. It defines the agent span attribute vocabulary as a superset of OTel GenAI semconv (`gen_ai.*`) plus the three `unityinflow.*` extensions OTel does not provide (cost, saturation, skill), with the OTLP value type for every attribute (CONV-03) and the large-payload handling rules. README links to it.

Purpose: This document IS the public API surface of the ecosystem — it is what KORE's instrumentation is written against and what makes "any gen_ai.*-emitting source renders usefully" a documented promise, not an implementation accident.
Output: A complete, semver'd convention doc with a single attribute/type contract table, payload rules, run-identity/outcome/precedence semantics, and a PII warning; README link.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/01-attribute-convention-domain-model/01-CONTEXT.md
@.planning/phases/01-attribute-convention-domain-model/01-RESEARCH.md
@.planning/phases/01-attribute-convention-domain-model/01-PATTERNS.md
</context>

<tasks>

<task type="auto">
  <name>Task 1: Write the versioned convention document + README link</name>
  <files>docs/agent-span-convention.md, README.md</files>
  <read_first>
    - .planning/phases/01-attribute-convention-domain-model/01-RESEARCH.md (Convention Document Contents — the 7 required sections; gen_ai.* Attribute Reference table; OTLP Value-Type Mapping; Payload Handling Rules; Security Domain PII warning; State of the Art rename notes)
    - .planning/phases/01-attribute-convention-domain-model/01-CONTEXT.md (locked decisions D-01 through D-14)
    - .planning/phases/01-attribute-convention-domain-model/01-PATTERNS.md (docs/agent-span-convention.md required-contents pointer)
    - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt (canonical key strings — the doc table must match these exactly; created in Plan 01)
    - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/UnityInFlowAttributes.kt (canonical unityinflow.* key strings)
  </read_first>
  <action>
    Write `docs/agent-span-convention.md` v1.0 with the 7 sections from RESEARCH Convention Document Contents. Every attribute key string in the doc MUST match the agent-semconv constants exactly.

    1. Versioning and status: semver 1.0; emitters stamp `unityinflow.schema_version` (string) per D-02. State it is a SUPERSET of OTel GenAI semconv (Development stability) and name the targeted upstream genai semconv version. Note the `gen_ai.system` to `gen_ai.provider.name` rename as precedent for the version stamp.

    2. A single attribute contract table with columns: Attribute key, OTLP value type, Required/Recommended/Opt-In, Units / scale, Notes. This table IS the contract; the OTLP value type column satisfies CONV-03. Include the standard gen_ai.* rows: operation.name, provider.name, request.model, response.model, usage.input_tokens (int), usage.output_tokens (int), agent.name, agent.id, tool.name, conversation.id, response.finish_reasons (string[]), input.messages / output.messages / system_instructions (any/JSON-string, Opt-In).

    3. The three unityinflow.* extensions plus reserved set (D-04, D-05, D-07): unityinflow.skill.name (string), unityinflow.skill.version (string, D-07), unityinflow.context.saturation_pct (double, 0-100 percent, extractor clamps), unityinflow.cost.usd (double, USD, no currency code / no micro-USD int — D-05), unityinflow.plan.step (int, D-07), unityinflow.outcome (string enum success|failure|partial|cancelled), unityinflow.schema_version (string semver), unityinflow.payload.truncated (boolean, set by receiver), unityinflow.payload.original_size_bytes (int).

    4. Tokens-only statement (D-06): explicitly state there is NO unityinflow.* token attribute — token counts come only from gen_ai.usage.input_tokens / output_tokens.

    5. Run identity and outcome semantics (D-08/09/10): trace = run; root span = run entry point; outcome = root span OTel status (OK to success, ERROR to failure) unless unityinflow.outcome overrides; agent identity = gen_ai.agent.name else resource service.name. Note: classify nodes by gen_ai.operation.name, NOT by span name (names are advisory).

    6. Payload handling rules (D-13/14): payload SOURCE keys are the standard gen_ai.input.messages / output.messages / system_instructions only — NO custom unityinflow.payload.* content keys. Cap = 256 KB per payload field; over-cap payloads are truncated BY THE RECEIVER (Phase 3) with unityinflow.payload.truncated=true and unityinflow.payload.original_size_bytes recording original size; replay surfaces truncation honestly. On a span, expect these as string attributes holding JSON.

    7. Extraction precedence statement (D-11): when unityinflow.* and gen_ai.* disagree on the same logical field, unityinflow.* wins.

    Plus a Security / sensitive-data section carrying OTel's warning forward: gen_ai.input.messages / output.messages likely contain sensitive information including user/PII data; payloads are opt-in (off by default) and SHOULD be redactable/truncatable at the emitter; downstream TTL is a later mitigation. Also note attribute values are untrusted strings that the Phase 5 UI must escape.

    Then update README.md: add a top-level link to docs/agent-span-convention.md (create README.md if absent, per CLAUDE.md every tool needs one — at minimum a title, one-line description, and the convention link).
  </action>
  <verify>
    <automated>cd /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/09-agent-tracer && test -f docs/agent-span-convention.md && grep -q 'unityinflow.context.saturation_pct' docs/agent-span-convention.md && grep -q 'unityinflow.cost.usd' docs/agent-span-convention.md && grep -q 'unityinflow.skill.name' docs/agent-span-convention.md && grep -q 'gen_ai.usage.input_tokens' docs/agent-span-convention.md && grep -q '256' docs/agent-span-convention.md && grep -qi 'OTLP value type' docs/agent-span-convention.md && grep -q 'agent-span-convention.md' README.md && echo CONVENTION_OK</automated>
  </verify>
  <acceptance_criteria>
    - `docs/agent-span-convention.md` exists with at least 60 lines.
    - The doc contains all three unityinflow.* extension keys: `unityinflow.context.saturation_pct`, `unityinflow.cost.usd`, `unityinflow.skill.name`.
    - The doc contains the standard token keys `gen_ai.usage.input_tokens` and `gen_ai.usage.output_tokens`, and explicitly states tokens come ONLY from gen_ai.usage.* (D-06).
    - The contract table has an OTLP value type column (`grep -qi 'OTLP value type'`).
    - The doc states the 256 KB payload cap and the truncation signalling keys `unityinflow.payload.truncated` and `unityinflow.payload.original_size_bytes`.
    - The doc states the precedence rule (unityinflow.* wins on conflict, D-11) and includes the PII/sensitive-data warning.
    - `README.md` contains a markdown link to `docs/agent-span-convention.md`.
    - Every attribute key string in the doc matches the agent-semconv constants from Plan 01 (no key drift).
  </acceptance_criteria>
  <done>The published, versioned convention document defines the full v1.0 attribute vocabulary with a per-attribute OTLP value type column, payload handling rules, run/outcome/precedence semantics, and a PII warning; README links to it. CONV-01 and CONV-03 documentary surface complete.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| convention doc → all downstream emitters/consumers | The doc is a documentary contract; its only "security" role is to correctly specify payload/PII handling obligations that downstream phases enforce |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-07 | Information Disclosure | prompt/completion payloads (PII/secrets) governed by the convention | mitigate (documentary) | The doc MUST carry OTel's sensitive-data warning, state payloads are opt-in and redactable/truncatable at the emitter, and define the 256 KB cap; this is the documentary mitigation the gate requires. LOW — no data is stored or transmitted in this phase |
| T-01-08 | Tampering | attacker-controlled attribute values later rendered in the Phase 5 UI | accept (document) | The doc notes attribute values are untrusted strings that the Phase 5 UI must escape; no rendering surface exists this phase. LOW |
| T-01-SC | Tampering | npm/pip/cargo/maven installs | accept | No package installs in this plan (markdown + README only). No checkpoint required |
</threat_model>

<verification>
- The convention doc verify command prints CONVENTION_OK (all required attribute keys, the 256 KB cap, the OTLP value type column, and the README link present).
- Manual readability check (VALIDATION.md Manual-Only): every v1.0 attribute has name + OTLP type + description, and payload-size rules are stated.
- Attribute keys in the doc match the agent-semconv constants (no drift against Plan 01).
</verification>

<success_criteria>
- A versioned (semver 1.0) convention document defines the three unityinflow.* extensions alongside the standard gen_ai.* attributes (CONV-01).
- Every attribute has its OTLP value type specified in the single contract table (CONV-03).
- Large-payload handling rules (256 KB cap + truncation signalling + gen_ai.* source keys) are documented (CONV-03).
- README links to the convention; PII warning carried forward.
</success_criteria>

<artifacts_produced>
## Artifacts this phase produces (Plan 03)

New files:
- `docs/agent-span-convention.md` — the published, versioned (semver 1.0) agent span attribute convention contract: a single attribute table with an OTLP value-type column, the three unityinflow.* extensions (skill.name, context.saturation_pct, cost.usd) + reserved set (skill.version, plan.step, outcome, schema_version, payload.truncated, payload.original_size_bytes), tokens-only-from-gen_ai statement, run-identity/outcome/precedence semantics, payload handling rules (256 KB cap), and the PII/sensitive-data warning
- `README.md` — project README linking the convention document
</artifacts_produced>

<output>
Create `.planning/phases/01-attribute-convention-domain-model/01-03-SUMMARY.md` when done
</output>
