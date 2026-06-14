---
phase: 01-attribute-convention-domain-model
plan: 04
type: execute
wave: 1
depends_on: ["01-02"]
gap_closure: true
files_modified:
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt
autonomous: true
requirements: [CONV-02, CONV-03]
must_haves:
  truths:
    - "A gen_ai span carrying gen_ai.provider.name extracts into AgentSpan.provider"
    - "A gen_ai span carrying gen_ai.agent.id extracts into AgentSpan.agentId"
    - "A gen_ai span carrying gen_ai.tool.call.id extracts into AgentSpan.toolCallId"
    - "A gen_ai span carrying gen_ai.conversation.id extracts into AgentSpan.conversationId"
    - "Each of the four new fields is null when its gen_ai.* key is absent (D-12 — no sentinel)"
    - "The four doc rows in docs/agent-span-convention.md §2 (Recommended) are now backed by typed fields the extractor reads — no silent data-loss path (closes WR-01)"
  artifacts:
    - path: "backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt"
      provides: "Four new nullable fields: provider, agentId, toolCallId, conversationId"
      contains: "val provider: String?"
    - path: "backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt"
      provides: "Reads gen_ai.provider.name/agent.id/tool.call.id/conversation.id via GenAiAttributes constants"
      contains: "GenAiAttributes.PROVIDER_NAME"
  key_links:
    - from: "backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt"
      to: "io.github.unityinflow.agentsemconv.GenAiAttributes (PROVIDER_NAME/AGENT_ID/TOOL_CALL_ID/CONVERSATION_ID)"
      via: "attrs.string(...) over the existing vendored constants"
      pattern: "GenAiAttributes\\.(PROVIDER_NAME|AGENT_ID|TOOL_CALL_ID|CONVERSATION_ID)"
---

<objective>
Close UAT gap (severity major, == code-review WR-01): four `gen_ai.*` attributes declared **Recommended** in the published Stable contract `docs/agent-span-convention.md` §2 — `gen_ai.provider.name`, `gen_ai.agent.id`, `gen_ai.tool.call.id`, `gen_ai.conversation.id` — have vendored constants in `agent-semconv` (`GenAiAttributes.PROVIDER_NAME`, `AGENT_ID`, `TOOL_CALL_ID`, `CONVERSATION_ID`) but no `AgentSpan` fields and are never read by `AgentSpanExtractor`. They are silently dropped at extraction. The user's decided fix is to **extend the code to match the contract** (not downgrade the doc): add four nullable fields and read them.

This is purely additive: four new `String?` fields, four new `attrs.string(...)` reads using constants that already exist, plus tests. No `unityinflow.*` precedence applies (these are gen_ai.* only). Nothing else is touched — no ClickHouse, no OTLP receivers, no `agent-semconv` changes (constants already present).

Purpose: Three of the four feed named v1 acceptance criteria (filter-by-agent via `gen_ai.agent.id`, filter-by-model/provider via `gen_ai.provider.name`, subagent/tool hierarchy via `gen_ai.tool.call.id`) and `gen_ai.conversation.id` underpins grouping spans into a run. Eliminating the silent data-loss path keeps the Stable contract honest.
Output: Updated `AgentSpan` + `AgentSpanExtractor`, extended fixture + test classes, `./gradlew test` green.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/01-attribute-convention-domain-model/01-UAT.md
@.planning/phases/01-attribute-convention-domain-model/01-REVIEW.md

# The four files the change targets — match their existing conventions exactly:
@backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
@backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
@backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt
@agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt

# Existing tests + fixtures to extend (do not rewrite — append):
@backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt
@backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt
@backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Add failing tests for the four gen_ai fields (RED)</name>
  <files>
    backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt,
    backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt,
    backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt
  </files>
  <behavior>
    - Populated: a span carrying gen_ai.provider.name / gen_ai.agent.id / gen_ai.tool.call.id / gen_ai.conversation.id extracts into AgentSpan.provider / agentId / toolCallId / conversationId respectively.
    - Absent: extracting from an empty attribute list yields null for all four new fields (D-12 — no sentinel).
  </behavior>
  <action>
    Extend the existing test suite to cover the four new gen_ai-only fields. Keep all changes additive — append to the existing files, do not rewrite passing tests or fixtures.

    In SpanFixtures.kt, add the four Recommended gen_ai.* keys to the `genAiOnlyAttrs` golden fixture using the existing `stringAttr` builder and the existing `GenAiAttributes` constants (PROVIDER_NAME, AGENT_ID, TOOL_CALL_ID, CONVERSATION_ID). Use distinct, recognisable literal values, e.g. provider "anthropic", agent id "agent-42", tool call id "call-7", conversation id "conv-abc". Optionally add them to `fullAttrs` as well for parity, but `genAiOnlyAttrs` is the load-bearing one.

    In AgentSpanExtractorGenAiOnlyTest.kt, add one `@Test` per field asserting the extracted `span` (already built from `genAiOnlyAttrs`) carries the expected value: `span.provider shouldBe "anthropic"`, `span.agentId shouldBe "agent-42"`, `span.toolCallId shouldBe "call-7"`, `span.conversationId shouldBe "conv-abc"`. Match the existing backtick test-name + Kotest `shouldBe` style; reuse the existing imports.

    In AgentSpanExtractorNullableTest.kt, extend the `a span missing ... extracts those fields as null` test (or add a dedicated test) to assert `span.provider.shouldBeNull()`, `span.agentId.shouldBeNull()`, `span.toolCallId.shouldBeNull()`, `span.conversationId.shouldBeNull()` for the empty-attrs span.

    These tests reference AgentSpan fields that do not exist yet, so the module will not compile until Task 2 — that is the intended RED state. Do NOT add the AgentSpan fields in this task.
  </action>
  <verify>
    <automated>cd backend &amp;&amp; ./gradlew compileTestKotlin 2>&amp;1 | grep -qiE "unresolved reference: (provider|agentId|toolCallId|conversationId)" &amp;&amp; echo RED-CONFIRMED</automated>
  </verify>
  <done>New tests reference `provider`, `agentId`, `toolCallId`, `conversationId` on AgentSpan; the test source fails to compile with unresolved-reference errors for those names (RED state confirmed — fields not yet added).</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Add the four nullable fields and extractor reads (GREEN)</name>
  <files>
    backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt,
    backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
  </files>
  <behavior>
    - AgentSpan gains provider, agentId, toolCallId, conversationId (all String?), following the file's existing nullable + KDoc conventions.
    - AgentSpanExtractor populates each from the matching GenAiAttributes constant via AttrView.string, absence -> null.
  </behavior>
  <action>
    Add four nullable fields to the `AgentSpan` data class (backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt), matching the file's existing one-KDoc-per-field nullable style:
    - `val provider: String?` — KDoc: GenAI provider name (`gen_ai.provider.name`), e.g. `anthropic`, `openai`.
    - `val agentId: String?` — KDoc: Stable agent identifier (`gen_ai.agent.id`).
    - `val toolCallId: String?` — KDoc: Identifier of a specific tool invocation (`gen_ai.tool.call.id`).
    - `val conversationId: String?` — KDoc: Groups related spans into a conversation/run (`gen_ai.conversation.id`).
    Place them sensibly among the existing fields (e.g. `provider` near `model`, `agentId` near `agentName`, `toolCallId` near `skillName`, `conversationId` with them or near identity fields). Keep absence -> null per D-12; do not invent defaults.

    In AgentSpanExtractor.extract (backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt), add four reads in the `AgentSpan(...)` constructor call using the existing `attrs.string(...)` coercion and the already-imported `GenAiAttributes` constants:
    - `provider = attrs.string(GenAiAttributes.PROVIDER_NAME)`
    - `agentId = attrs.string(GenAiAttributes.AGENT_ID)`
    - `toolCallId = attrs.string(GenAiAttributes.TOOL_CALL_ID)`
    - `conversationId = attrs.string(GenAiAttributes.CONVERSATION_ID)`
    These are gen_ai.* only — do NOT add any `unityinflow.*` precedence (`?:`) fallback. No new imports needed (`GenAiAttributes` is already imported). Use named arguments consistently with the surrounding constructor call. No raw key literals (CONV-01) — only constants.

    Constraints reminder: no `var`, no `!!`, immutable data class, ktlint-clean (kotlinter is wired into `check`).
  </action>
  <verify>
    <automated>cd backend &amp;&amp; ./gradlew test 2>&amp;1 | tail -5</automated>
  </verify>
  <done>`./gradlew test` passes (all backend tests green, including the new gen_ai field tests from Task 1); `AgentSpan` exposes `provider`/`agentId`/`toolCallId`/`conversationId` as `String?`; `AgentSpanExtractor` reads each via the corresponding `GenAiAttributes` constant with no `unityinflow.*` precedence.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| emitter (KORE/OTel) → extractor | Untrusted OTLP span attributes cross into the typed domain model |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-04-01 | Tampering | new `attrs.string(...)` reads in AgentSpanExtractor | mitigate | Reads go through existing `AttrView.string`, which returns null on absent/wrong-variant input and never throws (Security V5 / DoS, same path as all existing fields). No parsing of payload — verbatim string carry only. |
| T-01-04-02 | Information disclosure | conversationId / agentId values | accept | These are identifiers the emitter already chose to attach to the span; no new sink, storage, or log surface is added in this plan. |
| T-01-04-SC | Tampering | npm/pip/cargo installs | mitigate | No package-manager installs in this plan — all dependencies (kotlinx, JUnit, Kotest, opentelemetry-proto) are already on the classpath from Phase 01-01. Nothing to audit. |
</threat_model>

<verification>
- `cd backend && ./gradlew test` — full backend suite green, including the four new populated-field tests and the four absent-field null assertions.
- `cd backend && ./gradlew check` — ktlint (kotlinter) clean.
- Manual grep: `grep -c 'GenAiAttributes\.\(PROVIDER_NAME\|AGENT_ID\|TOOL_CALL_ID\|CONVERSATION_ID\)' backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt` returns 4.
- The four §2 Recommended doc rows in `docs/agent-span-convention.md` now have a typed field the extractor reads — WR-01 / UAT gap closed.
</verification>

<success_criteria>
- `AgentSpan` has exactly four new `String?` fields: `provider`, `agentId`, `toolCallId`, `conversationId`, each KDoc'd in the file's existing style.
- `AgentSpanExtractor` reads each via its `GenAiAttributes` constant using `AttrView.string`, with no `unityinflow.*` precedence and no raw literals.
- A gen_ai-only span populates all four; an empty span leaves all four null (D-12).
- No files outside the five listed in frontmatter are modified (no ClickHouse, OTLP, or agent-semconv changes).
- `./gradlew test` and `./gradlew check` pass; coverage on the extractor stays >80%.
</success_criteria>

<output>
Create `.planning/phases/01-attribute-convention-domain-model/01-04-SUMMARY.md` when done.
</output>
