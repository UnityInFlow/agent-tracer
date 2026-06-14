---
phase: 01-attribute-convention-domain-model
verified: 2026-06-14T19:00:00Z
status: passed
score: 4/4 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: human_needed
  previous_score: 4/4
  gaps_closed:
    - "WR-01: four published-but-dropped Recommended gen_ai.* contract attributes (gen_ai.provider.name, gen_ai.agent.id, gen_ai.tool.call.id, gen_ai.conversation.id) are now backed by typed AgentSpan fields read by AgentSpanExtractor"
  gaps_remaining: []
  regressions: []
---

# Phase 1: Attribute Convention & Domain Model Verification Report

**Phase Goal:** Any OTel-emitting agent source produces spans agent-tracer understands — the published attribute convention is the ecosystem's public contract, and the extractor turns raw OTLP attributes into typed agent semantics.
**Verified:** 2026-06-14T19:00:00Z
**Status:** passed
**Re-verification:** Yes — after gap-closure plan 01-04 closed UAT/WR-01

## Re-Verification Focus

The previous verification was `human_needed` for a single contract-fidelity discrepancy (WR-01): four `gen_ai.*` attributes declared **Recommended** in the published Stable contract (`docs/agent-span-convention.md` §2) had vendored `agent-semconv` constants but no `AgentSpan` field and were silently dropped at extraction. The developer decided to extend the code to match the contract. Plan 01-04 was executed. This re-verification confirms the gap is genuinely closed in code (not merely claimed) and that no regressions were introduced.

### WR-01 Gap Closure — Verified in Code

| Check | Evidence | Status |
| ----- | -------- | ------ |
| AgentSpan has the four fields | `AgentSpan.kt` lines 17 (`agentId: String?`), 19 (`provider: String?`), 25 (`toolCallId: String?`), 27 (`conversationId: String?`) — all nullable, KDoc'd in the file's existing style | ✓ CLOSED |
| Extractor reads all four via vendored constants | `AgentSpanExtractor.kt` lines 41–42, 49–50: `agentId = attrs.string(GenAiAttributes.AGENT_ID)`, `provider = attrs.string(GenAiAttributes.PROVIDER_NAME)`, `toolCallId = attrs.string(GenAiAttributes.TOOL_CALL_ID)`, `conversationId = attrs.string(GenAiAttributes.CONVERSATION_ID)`. `grep -c` of the four constants in the extractor = **4**. No raw key literals (CONV-01). No `unityinflow.*` precedence (these are gen_ai.* only — correct). | ✓ CLOSED |
| Contract §2 rows are now backed | `docs/agent-span-convention.md` §2 lines 58/64/66/67 declare all four as Recommended `string_value`; each now maps to a typed field the extractor reads — no silent data-loss path | ✓ CLOSED |
| Populated-field tests exist and pass | `AgentSpanExtractorGenAiOnlyTest.kt` lines 47–63: `provider shouldBe "anthropic"`, `agentId shouldBe "agent-42"`, `toolCallId shouldBe "call-7"`, `conversationId shouldBe "conv-abc"`. All four ran and passed (test-results XML). | ✓ CLOSED |
| Null-on-absence tests exist and pass (D-12) | `AgentSpanExtractorNullableTest.kt` lines 26–31: all four `.shouldBeNull()` for an empty-attrs span. Ran and passed. | ✓ CLOSED |
| Golden fixture carries the keys | `SpanFixtures.kt` lines 84–87 add the four keys to `genAiOnlyAttrs` via `stringAttr(GenAiAttributes.*)` | ✓ CLOSED |

## Goal Achievement

### Observable Truths

| #   | Truth | Status | Evidence |
| --- | ----- | ------ | -------- |
| 1   | A developer can read a published, versioned convention document defining `unityinflow.skill.name`, `unityinflow.context.saturation_pct`, `unityinflow.cost.usd` alongside standard `gen_ai.*` token/model/agent attributes | ✓ VERIFIED | `docs/agent-span-convention.md` exists with all three unityinflow.* keys + gen_ai.* token/model/agent rows incl. the four Recommended rows (§2 lines 58/64/66/67); README links it. Unchanged from prior pass. |
| 2   | The convention document specifies the OTLP value type for every attribute and large-payload handling rules | ✓ VERIFIED | §2 rows carry an explicit `string_value`/value-type column per attribute (incl. the four new ones); 256 KB cap + truncation keys present. Unchanged from prior pass. |
| 3   | A span carrying only standard `gen_ai.*` attributes is extracted into typed agent fields with no emitter changes (CONV-02) | ✓ VERIFIED | `AgentSpanExtractorGenAiOnlyTest` (12 tests incl. the 4 new provider/agentId/toolCallId/conversationId assertions) all green; tokens typed `Long?`. The four formerly-dropped gen_ai.* keys now extract into typed fields. |
| 4   | A span carrying `unityinflow.*` extensions is extracted into typed fields with precedence over generic fallbacks verified by tests (CONV-03) | ✓ VERIFIED | Precedence + clamp + nullable + value-type suites all green. `skillName` prefers `unityinflow.skill.name` over `gen_ai.tool.name`; outcome override verified. Unchanged from prior pass. |

**Score:** 4/4 truths verified

### Required Artifacts (re-verification — changed files only)

| Artifact | Expected | Status | Details |
| -------- | -------- | ------ | ------- |
| `backend/.../domain/AgentSpan.kt` | +4 nullable fields | ✓ VERIFIED | `provider`, `agentId`, `toolCallId`, `conversationId` all `String?`, KDoc'd; data class remains immutable, no `var`/`!!`. |
| `backend/.../convention/AgentSpanExtractor.kt` | reads 4 new keys via constants | ✓ VERIFIED | 4 `attrs.string(GenAiAttributes.*)` reads; grep count = 4; no raw literals; no spurious `unityinflow.*` precedence. |
| `backend/.../test/.../SpanFixtures.kt` | fixture carries 4 keys | ✓ VERIFIED | lines 84–87. |
| `backend/.../test/.../AgentSpanExtractorGenAiOnlyTest.kt` | 4 populated-field tests | ✓ VERIFIED | lines 47–63, all green. |
| `backend/.../test/.../AgentSpanExtractorNullableTest.kt` | 4 null-on-absence asserts | ✓ VERIFIED | lines 26–31, all green. |

Other artifacts (version catalog, agent-semconv constants, AttrView, convention doc, README) were VERIFIED in the initial pass and are unchanged by plan 01-04 (regression check: build still green, no edits to those files per 01-04 frontmatter).

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| Full backend suite passes (forced rerun) | `./gradlew :backend:test --rerun-tasks` | BUILD SUCCESSFUL; 37 tests across 7 classes, 0 failures, 0 errors | ✓ PASS |
| provider from gen_ai.provider.name | `AgentSpanExtractorGenAiOnlyTest."provider comes from gen_ai provider name"` | green | ✓ PASS |
| agentId from gen_ai.agent.id | `..."agent id comes from gen_ai agent id"` | green | ✓ PASS |
| toolCallId from gen_ai.tool.call.id | `AgentSpanExtractorGenAiOnlyTest` (tool-call test) | green | ✓ PASS |
| conversationId from gen_ai.conversation.id | `..."conversation id comes from gen_ai conversation id"` | green | ✓ PASS |
| all four null on empty span (D-12) | `AgentSpanExtractorNullableTest."a span missing the gen_ai provider agent tool-call and conversation ids..."` | green | ✓ PASS |
| extractor reads all 4 constants | `grep -c GenAiAttributes.(PROVIDER_NAME\|AGENT_ID\|TOOL_CALL_ID\|CONVERSATION_ID)` | 4 | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
| ----------- | -------------- | ----------- | ------ | -------- |
| CONV-01 | 01-01, 01-03 | Convention published as documented contract | ✓ SATISFIED | agent-semconv constants + `docs/agent-span-convention.md` v1.0; REQUIREMENTS.md marks Complete. |
| CONV-02 | 01-02, 01-04 | Extractor reads standard OTel GenAI semconv with no emitter changes | ✓ SATISFIED | GenAiOnly suite (12 tests) incl. the four new Recommended-key reads. |
| CONV-03 | 01-02, 01-03, 01-04 | Convention specifies OTLP value types + large-payload handling | ✓ SATISFIED | Doc value-type column (incl. 4 new rows) + truncation keys; AttrView preserves types. |

All requirement IDs declared across all four PLAN frontmatters — CONV-01, CONV-02, CONV-03 — appear in REQUIREMENTS.md mapped to Phase 1 (lines 90–92, all `Complete`). No orphaned requirements; no plan-declared ID is missing from REQUIREMENTS.md.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
| ---- | ---- | ------- | -------- | ------ |
| (none) | — | No `var` / `!!` / TODO / FIXME / XXX / TBD / PLACEHOLDER in modified main source | — | Conforms to CLAUDE.md Kotlin rules |

### Gaps Summary

**No gaps. Phase goal achieved.** The sole open item from the prior verification (WR-01, surfaced as a human decision) has been resolved by the developer's chosen fix and confirmed in code: all four §2 Recommended `gen_ai.*` attributes (`provider.name`, `agent.id`, `tool.call.id`, `conversation.id`) are now nullable `AgentSpan` fields read by `AgentSpanExtractor` through vendored `GenAiAttributes` constants, with populated and null-on-absence tests green. The published Stable contract no longer over-promises — every §2 row is backed by a typed field with no silent data-loss path. Backend suite: 37 tests, 0 failures on a forced rerun. No regressions to the previously-verified artifacts. All 4 ROADMAP success criteria observably true; all 3 requirements satisfied.

---

_Verified: 2026-06-14T19:00:00Z_
_Verifier: Claude (gsd-verifier)_
