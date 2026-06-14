---
phase: 01-attribute-convention-domain-model
reviewed: 2026-06-14T00:00:00Z
depth: deep
files_reviewed: 5
files_reviewed_list:
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt
findings:
  critical: 0
  warning: 0
  info: 2
  total: 2
status: clean
---

# Phase 01: Code Review Report — Gap-Closure Plan 01-04 (extend-agentspan-gen-ai-keys)

**Reviewed:** 2026-06-14
**Depth:** deep (cross-file: extractor ↔ domain ↔ vendored constants ↔ published contract)
**Files Reviewed:** 5
**Status:** clean

> This review covers **only the gap-closure changes in plan 01-04**
> (`git diff fa9ec64 2a560bb`), which add four nullable `gen_ai.*` fields to
> `AgentSpan` and read them in `AgentSpanExtractor`. It supersedes prior finding
> **WR-01** from the earlier full-phase review (see "Prior findings status"
> below). The other prior findings (WR-02..WR-05, IN-01..IN-06) were out of scope
> for this plan and are recorded as still-open at the end of this file.

## Summary

The gap closure adds four nullable `gen_ai.*` fields to `AgentSpan`
(`agentId`, `provider`, `toolCallId`, `conversationId`) and reads them in
`AgentSpanExtractor`. The change is correct, minimal, idiomatic, and fully
test-covered. No bugs, security issues, or convention violations found.

Adversarial checklist — all verified:

- **Vendored constants, not literals (CONV-01):** The four reads use
  `GenAiAttributes.AGENT_ID`, `.PROVIDER_NAME`, `.TOOL_CALL_ID`,
  `.CONVERSATION_ID` (extractor lines 41-42, 49-50). No raw `gen_ai.*` literal
  was introduced in the extractor, domain, or fixtures. Constants confirmed in
  `agent-semconv/.../GenAiAttributes.kt` with the exact contract key strings
  (`gen_ai.provider.name`, `gen_ai.agent.id`, `gen_ai.tool.call.id`,
  `gen_ai.conversation.id`).
- **Null-on-absence preserved (D-12):** All four go through `attrs.string(...)`,
  which is `hasStringValue()`-gated in `AttrView` — returns `null` on absent key
  OR wrong OTLP variant, never throws, no lexical coercion. The new nullable test
  asserts all four are null on an empty view.
- **Contract fully matched — no remaining silently-dropped Recommended keys:**
  Traced every row of `docs/agent-span-convention.md §2` against the extractor.
  Both the standard `gen_ai.*` and `unityinflow.*` tables are now fully read. The
  four keys closed here were the only previously-unread Recommended keys
  (`gen_ai.request.model` is already read as the `model` fallback). Coverage is
  complete.
- **Kotlin conventions:** `AgentSpan` remains an immutable `data class` with only
  `val` members; no `var`, no `!!`, no non-idiomatic constructs. The extractor
  builds `AgentSpan(...)` with **named arguments**, so the four new fields carry
  zero positional-mismatch risk despite being interleaved among existing fields.
- **Tests:** Positive assertions in `AgentSpanExtractorGenAiOnlyTest` (each field
  resolves to its fixture value: `anthropic`, `agent-42`, `call-7`, `conv-abc`)
  and negative assertions in `AgentSpanExtractorNullableTest` (each field null on
  empty). Fixtures in `SpanFixtures.genAiOnlyAttrs` use vendored constants and the
  correct `stringAttr` (`string_value`) variant. Test naming follows the ecosystem
  backtick style.

## Info

### IN-A: New gen_ai fields are single-source reads with no fallback chain (by design)

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt:41-42,49-50`
**Issue:** Unlike `agentName`, `model`, and `skillName`, the four new fields have
no `?:` fallback. This is correct — the contract defines no `unityinflow.*` or
resource alternative for these keys, and D-11 precedence only applies where two
keys supply the same logical field. Recorded so the asymmetry is not mistaken for
an omission in a later review.
**Fix:** None required.

### IN-B: `fullAttrs` fixture does not carry the four new gen_ai ids

**File:** `backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt:101-122`
**Issue:** The four new keys were added to `genAiOnlyAttrs` (lines 84-87) but not
to `fullAttrs`. Behavior is fully covered by the gen_ai-only and nullable suites,
so there is no correctness gap today. If a future full-span/precedence test
asserts these fields are populated on a "full" span, `fullAttrs` would need them.
**Fix:** Optional — add the four `stringAttr(...)` lines to `fullAttrs` when/if a
full-span test asserts on them.

---

## Prior findings status (earlier full-phase review)

- **WR-01 (contract attributes silently dropped: provider, agent.id, tool.call.id,
  conversation.id)** — **RESOLVED by plan 01-04.** All four fields are now present
  on `AgentSpan` and read via vendored constants with null-on-absence semantics,
  with positive and negative tests.
- **WR-02..WR-05, IN-01..IN-06** — out of scope for plan 01-04; **not re-reviewed
  here and presumed still open.** They concern build config (protobuf pin, coverage
  gate, unused `mockk`), `stringList`/empty-string `AttrView` semantics, POM email,
  unsupported OTLP variants, the `embeddings` enum gap, `fromOtel` case-sensitivity,
  `int()` narrowing, and the SNAPSHOT publication name — none touched by this plan.

---

_Reviewed: 2026-06-14_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: deep_
