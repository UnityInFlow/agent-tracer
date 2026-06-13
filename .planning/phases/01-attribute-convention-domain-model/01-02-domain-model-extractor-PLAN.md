---
phase: 01-attribute-convention-domain-model
plan: 02
type: execute
wave: 2
depends_on: ["01-01"]
files_modified:
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentOutcome.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentOperation.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorOperationTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorValueTypeTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorPrecedenceTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorClampTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorPayloadTest.kt
autonomous: true
requirements: [CONV-02, CONV-03]
must_haves:
  truths:
    - "A span carrying only standard gen_ai.* attributes extracts into typed agent fields (agent, model, tokens, operation) with no unityinflow.* present"
    - "When unityinflow.* and gen_ai.* supply the same logical field and disagree, unityinflow.* wins (precedence test-verified)"
    - "OTLP value types are preserved: int_value lands in Long?, double_value in Double?, bool_value in Boolean? — never stringified"
    - "saturation_pct clamps to [0,100]; missing fields are null (no sentinel); the extractor never throws on malformed/missing input"
    - "Payload cap/truncation contract is represented in the model (payloadTruncated, payloadOriginalSizeBytes)"
  artifacts:
    - path: "backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt"
      provides: "Immutable typed domain model with nullable fields"
      contains: "data class AgentSpan"
    - path: "backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt"
      provides: "Pure OTLP-attributes → AgentSpan extractor with unityinflow.* precedence"
      contains: "fun extract"
    - path: "backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt"
      provides: "Type-preserving AnyValue coercion helpers (string/long/double/bool/stringList)"
      contains: "AttrView"
  key_links:
    - from: "backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt"
      to: "io.github.unityinflow.agentsemconv (GenAiAttributes/UnityInFlowAttributes/GenAiOperation)"
      via: "import of vendored key constants"
      pattern: "agentsemconv"
    - from: "backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt"
      to: "opentelemetry-proto KeyValue/AnyValue"
      via: "reads AnyValue oneof variant per attribute type"
      pattern: "AnyValue"
---

<objective>
Build the pure-Kotlin attribute extractor and its immutable typed domain model. The extractor turns a parsed OTLP span's attribute list (plus resource attributes) into an `AgentSpan` of nullable typed fields, reading standard `gen_ai.*` so any gen_ai-emitting source renders usefully with zero emitter changes (CONV-02), preserving OTLP value types per attribute and representing the payload cap/truncation contract (CONV-03). Precedence (`unityinflow.*` wins), clamping, and nullability are exhaustively test-verified — this is a roadmap success criterion.

Purpose: This is the highest test-ROI surface in the project and the contract that gates Phase 2's typed ClickHouse columns. Stringifying typed values here would break numeric aggregation downstream (Pitfall 1).
Output: `AgentSpan`/`AgentOutcome`/`AgentOperation` domain types, an `AttrView` coercion layer over OTLP `AnyValue`, the `AgentSpanExtractor`, and the full extractor test suite mapped to VALIDATION.md.
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
@.planning/phases/01-attribute-convention-domain-model/01-VALIDATION.md
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Domain model + AttrView coercion layer</name>
  <files>backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt, backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentOutcome.kt, backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentOperation.kt, backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt, backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt, backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorValueTypeTest.kt</files>
  <read_first>
    - .planning/phases/01-attribute-convention-domain-model/01-RESEARCH.md (System Architecture Diagram for AgentSpan field list; OTLP Value-Type Mapping table; Architectural Responsibility Map tier boundary — domain/convention must not import infra)
    - .planning/phases/01-attribute-convention-domain-model/01-PATTERNS.md (AgentSpan/AgentOutcome idiom; AttrView coercion helpers spec)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/src/main/kotlin/io/github/unityinflow/kore/core/TokenUsage.kt (immutable data class house style)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentResult.kt (sealed class house style)
    - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt (key constants to import; created in Plan 01)
  </read_first>
  <behavior>
    - Test (ValueType): an attribute carried as OTLP int_value (e.g. gen_ai.usage.input_tokens=1234) reads via `AttrView.long(key)` to `1234L` — NOT the string "1234".
    - Test (ValueType): a double_value (unityinflow.cost.usd=0.05) reads via `AttrView.double(key)` to `0.05`.
    - Test (ValueType): a bool_value (unityinflow.payload.truncated=true) reads via `AttrView.bool(key)` to `true`.
    - Test (ValueType): a string-typed value requested as `.long(key)` returns null (no silent lexical parse) — and absent keys return null for every accessor.
    - Test (ValueType): finish_reasons array_value of strings reads via `AttrView.stringList(key)` to a `List<String>`.
  </behavior>
  <action>
    Create the domain types in package `io.github.unityinflow.agenttracer.domain` and the coercion layer in `io.github.unityinflow.agenttracer.convention`. These packages MUST NOT import Ktor/gRPC/ClickHouse (tier boundary, RESEARCH Architectural Responsibility Map) — only opentelemetry-proto types are permitted in the convention layer.

    AgentSpan.kt — immutable `data class AgentSpan` of nullable typed fields (D-12, no sentinels): `agentName: String?`, `model: String?`, `skillName: String?`, `skillVersion: String?`, `inputTokens: Long?`, `outputTokens: Long?`, `costUsd: Double?`, `saturationPct: Double?`, `planStep: Int?`, `operation: AgentOperation?`, `outcome: AgentOutcome`, `inputPayload: String?`, `outputPayload: String?`, `systemInstructions: String?`, `payloadTruncated: Boolean?`, `payloadOriginalSizeBytes: Long?`, `finishReasons: List<String>?`, `schemaVersion: String?`. Token fields are `Long?` to preserve OTLP int64 (Pitfall 1) — never String. One-line KDoc.

    AgentOperation.kt — domain enum mirroring the operation classification the extractor produces. Reuse `GenAiOperation` from agent-semconv if it already matches; if a domain-side enum is preferred, map from `GenAiOperation`. Keep one source of truth — prefer importing `GenAiOperation` directly into AgentSpan.operation rather than duplicating. (If you import agent-semconv's enum, delete this file and update AgentSpan's import — note the decision in the SUMMARY.)

    AgentOutcome.kt — sealed class or enum for `success|failure|partial|cancelled` (D-09). Provide a factory deriving outcome from OTel status (OK→success, ERROR→failure) with an optional `unityinflow.outcome` string override.

    AttrView.kt — a wrapper over the OTLP `List<KeyValue>` (or a `Map<String, AnyValue>`) exposing type-preserving accessors: `string(key): String?`, `long(key): Long?`, `double(key): Double?`, `bool(key): Boolean?`, `stringList(key): List<String>?`. Each reads the matching `AnyValue` oneof variant (string_value/int_value/double_value/bool_value/array_value) and returns null when the key is absent OR carries the wrong variant — do NOT silently lexically parse a string into a number (strict per RESEARCH Open Question Q2; record the strict choice in SUMMARY). Confirm exact AnyValue field spellings against the opentelemetry-proto common proto.

    SpanFixtures.kt (test) — reusable builders/fixtures: a GOLDEN gen_ai-only span (no unityinflow.*) and a FULL unityinflow.* span, plus helpers to construct OTLP KeyValue lists with specific AnyValue variants. Reused across all extractor tests (VALIDATION Wave 0 fixtures).

    Write AgentSpanExtractorValueTypeTest.kt FIRST per the <behavior> block, asserting AttrView preserves each OTLP type and returns null on wrong-variant/absent.
  </action>
  <verify>
    <automated>cd /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/09-agent-tracer && ./gradlew :backend:test --tests "*ValueType*"</automated>
  </verify>
  <acceptance_criteria>
    - `./gradlew :backend:test --tests "*ValueType*"` exits 0.
    - `grep -c 'String' backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt` confirms tokens are NOT typed as String: `grep -n 'inputTokens' backend/src/main/.../AgentSpan.kt` shows `Long?`.
    - `grep -rn 'import io.ktor\|import io.grpc\|import com.clickhouse' backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention` returns no matches (tier boundary holds).
    - ValueType test asserts a string requested as `.long()` returns null.
    - No `var`, no `!!` without a safety comment in created files.
  </acceptance_criteria>
  <done>Domain model and AttrView coercion layer exist; OTLP value types are preserved and the wrong-variant/absent paths return null; convention/domain packages import no infra; ValueType test green.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: AgentSpanExtractor with precedence, clamp, nullability, gen_ai-only, operation, payload</name>
  <files>backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt, backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt, backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorOperationTest.kt, backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorPrecedenceTest.kt, backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorClampTest.kt, backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt, backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorPayloadTest.kt</files>
  <read_first>
    - .planning/phases/01-attribute-convention-domain-model/01-RESEARCH.md (Code Examples: priority-ordered extract function; Architecture Patterns 1-3; Payload Handling Rules; Anti-Patterns)
    - .planning/phases/01-attribute-convention-domain-model/01-VALIDATION.md (Per-Task Verification Map: the named test commands *Precedence*/*Clamp*/*Nullable*/*GenAiOnly*/*Operation*/*Payload*)
    - .planning/phases/01-attribute-convention-domain-model/01-PATTERNS.md (AgentSpanExtractor pure-function spec; extractor never throws)
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt (coercion accessors from Task 1)
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt (golden + full fixtures from Task 1)
    - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/UnityInFlowAttributes.kt (precedence keys)
  </read_first>
  <behavior>
    - Test (GenAiOnly): the golden gen_ai-only span extracts agentName from gen_ai.agent.name, model from gen_ai.response.model (falling back to gen_ai.request.model), inputTokens/outputTokens from gen_ai.usage.*, operation from gen_ai.operation.name — with ALL unityinflow.* fields null. Agent identity falls back to resource service.name when gen_ai.agent.name absent (D-10).
    - Test (Operation): a span with gen_ai.operation.name=invoke_agent yields operation=INVOKE_AGENT; execute_tool yields EXECUTE_TOOL; an unknown operation yields null (no throw).
    - Test (Precedence): when both unityinflow.skill.name and gen_ai.tool.name are present and disagree, skillName == the unityinflow value (D-11). Document at least one other field where unityinflow.* wins.
    - Test (Clamp): unityinflow.context.saturation_pct=137.0 extracts to saturationPct==100.0; -5.0 extracts to 0.0 (D-04).
    - Test (Nullable): a span missing cost/saturation/skill extracts those fields as null — no 0.0 sentinel (D-12).
    - Test (Payload): inputPayload/outputPayload are carried verbatim (never JSON-parsed); payloadTruncated reads from unityinflow.payload.truncated and payloadOriginalSizeBytes from unityinflow.payload.original_size_bytes; a span with no payload attributes yields null payload fields (opt-in, Pitfall 4).
    - Test (robustness): extracting from an empty attribute list, or attributes with wrong OTLP variants, returns an AgentSpan with null fields and never throws (Security V5 / DoS mitigation).
  </behavior>
  <action>
    Create `AgentSpanExtractor` in package `io.github.unityinflow.agenttracer.convention` as a pure function (no I/O) per RESEARCH Code Examples. Signature: `fun extract(attrs: AttrView, resource: AttrView): AgentSpan` (resource carries service.name for the D-10 fallback). Use the vendored constants from agent-semconv — no raw key literals.

    Field-by-field priority-ordered lookup (Pattern 2, D-11): for each logical field read the `unityinflow.*` key first, then the `gen_ai.*` standard key, then resource fallback, else null.
    - agentName = gen_ai.agent.name else resource service.name (D-10).
    - model = gen_ai.response.model else gen_ai.request.model.
    - skillName = unityinflow.skill.name else gen_ai.tool.name (generic fallback for tool nodes).
    - skillVersion = unityinflow.skill.version (String?, D-07).
    - inputTokens/outputTokens = gen_ai.usage.input_tokens / output_tokens (Long?, D-06 — the ONLY token source).
    - costUsd = unityinflow.cost.usd (Double?, D-05 — USD only, no currency code, no micro-USD int).
    - saturationPct = unityinflow.context.saturation_pct then `?.coerceIn(0.0, 100.0)` (D-04 clamp).
    - planStep = unityinflow.plan.step (Int?, D-07).
    - operation = GenAiOperation.fromOtel(gen_ai.operation.name).
    - outcome = derive from OTel span status, overridden by unityinflow.outcome (D-09).
    - inputPayload/outputPayload/systemInstructions = gen_ai.input.messages / output.messages / system_instructions, carried verbatim as String? — NEVER parsed (D-14, Anti-Pattern).
    - payloadTruncated = unityinflow.payload.truncated (Boolean?); payloadOriginalSizeBytes = unityinflow.payload.original_size_bytes (Long?).
    - finishReasons = gen_ai.response.finish_reasons (List<String>?).
    - schemaVersion = unityinflow.schema_version.

    The extractor MUST NEVER throw on malformed/missing/mistyped input — rely on AttrView returning null (CLAUDE.md: nullable/sealed over exceptions; Security V5).

    Write the seven test files FIRST per the <behavior> block, using the exact VALIDATION.md test-name patterns so the sampling commands match: GenAiOnly, Operation, Precedence, Clamp, Nullable, Payload. Use SpanFixtures, JUnit 5 @Test + Kotest shouldBe. Aim for >80% coverage of the extractor.
  </action>
  <verify>
    <automated>cd /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/09-agent-tracer && ./gradlew :backend:test</automated>
  </verify>
  <acceptance_criteria>
    - `./gradlew :backend:test --tests "*Precedence*"` exits 0 (unityinflow.* wins on conflict — roadmap success criterion 4).
    - `./gradlew :backend:test --tests "*Clamp*"` exits 0 (saturation clamps to [0,100]).
    - `./gradlew :backend:test --tests "*Nullable*"` exits 0 (missing → null, no sentinel).
    - `./gradlew :backend:test --tests "*GenAiOnly*"` exits 0 (gen_ai-only span → typed fields, zero unityinflow.* — success criterion 3).
    - `./gradlew :backend:test --tests "*Operation*"` exits 0; `./gradlew :backend:test --tests "*Payload*"` exits 0.
    - `./gradlew :backend:test` full module green.
    - `grep -rn 'gen_ai\.\|unityinflow\.' backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt` returns no matches (uses agent-semconv constants, not literals).
    - A test asserts `extract` on an empty AttrView does not throw and returns null-field AgentSpan.
  </acceptance_criteria>
  <done>AgentSpanExtractor produces typed AgentSpan with unityinflow.* precedence, saturation clamping, null-on-absence, verbatim payloads, and never throws; full backend test suite green; CONV-02 and CONV-03 success criteria 3 & 4 test-verified.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| (upstream emitter) → extractor | The extractor consumes an already-parsed OTLP attribute list; attribute keys, types, and values are UNTRUSTED (wrong OTLP variants, out-of-range numbers, oversized/malicious strings) |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-03 | Denial of Service | AgentSpanExtractor / AttrView on malformed or oversized attribute input | mitigate | Pure extractor never throws: AttrView returns null on absent/wrong-variant; saturation `coerceIn(0,100)`; no recursion/parsing of payload JSON (carried verbatim). LOW severity — pure function, no I/O, no unbounded allocation in this phase (256 KB payload cap is enforced by the Phase 3 receiver per D-13) |
| T-01-04 | Tampering | attacker-controlled attribute values (skill name, messages) later rendered in Phase 5 UI | accept (defer) | Out of scope for extraction; values carried verbatim as untrusted strings. Phase 5 UI MUST escape them — recorded as a downstream obligation in the convention doc (Plan 03). LOW now (no rendering surface this phase) |
| T-01-05 | Tampering | integer/double coercion safety | mitigate | int_value → Long? (no int32 overflow truncation); double_value → Double? with no NaN-producing arithmetic; wrong-variant values return null rather than coercing. LOW — no arithmetic beyond clamp |
| T-01-06 | Information Disclosure | prompt/completion payloads may contain PII/secrets | accept (document) | Payloads are opt-in (off by default in OTel) and carried verbatim; persistence/TTL is a Phase 2/3 concern. The PII warning is documented in the convention (Plan 03). No persistence this phase — LOW |
</threat_model>

<verification>
- `./gradlew :backend:test` green; all VALIDATION.md named test patterns (Precedence/Clamp/Nullable/GenAiOnly/Operation/Payload/ValueType) pass.
- `./gradlew test` (full multi-module) green.
- `./gradlew ktlintCheck` clean.
- No raw attribute key literals in the extractor; no infra imports in domain/convention.
</verification>

<success_criteria>
- gen_ai-only span extracts to typed agent fields with zero unityinflow.* (CONV-02, success criterion 3).
- unityinflow.* precedence over gen_ai.* is test-verified (success criterion 4).
- OTLP value types preserved per attribute; saturation clamped; missing → null; payloads verbatim with truncation contract represented (CONV-03).
- Extractor never throws on malformed input.
</success_criteria>

<artifacts_produced>
## Artifacts this phase produces (Plan 02)

New Kotlin symbols:
- `data class AgentSpan` (package `io.github.unityinflow.agenttracer.domain`) — nullable typed fields: agentName, model, skillName, skillVersion (String?); inputTokens, outputTokens, payloadOriginalSizeBytes (Long?); costUsd, saturationPct (Double?); planStep (Int?); operation (GenAiOperation?); outcome (AgentOutcome); inputPayload, outputPayload, systemInstructions, schemaVersion (String?); payloadTruncated (Boolean?); finishReasons (List<String>?)
- `AgentOutcome` (sealed class or enum, package `...domain`) — variants success/failure/partial/cancelled + factory from OTel status with unityinflow.outcome override
- `AgentOperation` (package `...domain`) — domain operation classification (may be an alias/import of agentsemconv.GenAiOperation; decision recorded in SUMMARY)
- `AttrView` (package `io.github.unityinflow.agenttracer.convention`) — accessors string/long/double/bool/stringList over OTLP KeyValue/AnyValue
- `AgentSpanExtractor` with `fun extract(attrs: AttrView, resource: AttrView): AgentSpan` (package `...convention`)

New test files:
- `SpanFixtures.kt`, `AgentSpanExtractorGenAiOnlyTest.kt`, `AgentSpanExtractorOperationTest.kt`, `AgentSpanExtractorValueTypeTest.kt`, `AgentSpanExtractorPrecedenceTest.kt`, `AgentSpanExtractorClampTest.kt`, `AgentSpanExtractorNullableTest.kt`, `AgentSpanExtractorPayloadTest.kt`
</artifacts_produced>

<output>
Create `.planning/phases/01-attribute-convention-domain-model/01-02-SUMMARY.md` when done
</output>
