---
phase: 01-attribute-convention-domain-model
plan: 04
subsystem: api
tags: [kotlin, otlp, gen_ai, semconv, agent-span, tdd]

# Dependency graph
requires:
  - phase: 01-02-domain-model-extractor
    provides: AgentSpan data class + AgentSpanExtractor + AttrView + SpanFixtures test suite
provides:
  - Four nullable AgentSpan fields (provider, agentId, toolCallId, conversationId) backing the §2 Recommended gen_ai.* rows
  - AgentSpanExtractor reads gen_ai.provider.name / agent.id / tool.call.id / conversation.id via vendored GenAiAttributes constants
  - Closure of WR-01 / UAT major gap — no silent data-loss path for the four Recommended gen_ai.* keys
affects: [otlp-ingestion, query-api, frontend-dag, filter-by-agent, filter-by-provider, subagent-hierarchy]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "gen_ai.* only fields read via a single attrs.string(GenAiAttributes.*) with no unityinflow.* precedence (?:)"
    - "Additive TDD: extend genAiOnlyAttrs golden fixture + append tests, never rewrite passing tests"

key-files:
  created: []
  modified:
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt

key-decisions:
  - "Fixed the gap by extending code to match the published Stable contract, not by downgrading the doc"
  - "No unityinflow.* precedence on the four new fields — they are gen_ai.* only"
  - "Absence -> null (D-12) preserved; no sentinel defaults invented"

patterns-established:
  - "Pattern 1: New Recommended gen_ai.* keys land as nullable AgentSpan fields read through GenAiAttributes constants — never raw literals (CONV-01)"

requirements-completed: [CONV-02, CONV-03]

# Metrics
duration: 2 min
completed: 2026-06-14
---

# Phase 01 Plan 04: Extend AgentSpan with gen_ai keys Summary

**Added four nullable gen_ai.* fields (provider, agentId, toolCallId, conversationId) to AgentSpan and wired AgentSpanExtractor to read them, closing the WR-01 / UAT silent data-loss gap against the Stable convention contract.**

## Performance

- **Duration:** 2 min
- **Started:** 2026-06-14T18:39:32Z
- **Completed:** 2026-06-14T18:41:15Z
- **Tasks:** 2 (TDD: RED + GREEN)
- **Files modified:** 5

## Accomplishments
- Four `String?` fields added to `AgentSpan` with one-KDoc-per-field style: `provider` (gen_ai.provider.name), `agentId` (gen_ai.agent.id), `toolCallId` (gen_ai.tool.call.id), `conversationId` (gen_ai.conversation.id).
- `AgentSpanExtractor` now reads each via `attrs.string(GenAiAttributes.*)` using the already-imported vendored constants — no raw key literals (CONV-01), no `unityinflow.*` precedence.
- Golden `genAiOnlyAttrs` fixture extended with the four keys; new populated-field and null-on-absence tests assert correct extraction (D-12, no sentinel).
- Eliminated the silent drop of four Recommended attributes — the §2 doc rows in `docs/agent-span-convention.md` are now backed by typed fields the extractor reads.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add failing tests for the four gen_ai fields (RED)** - `58e1ea9` (test)
2. **Task 2: Add the four nullable fields and extractor reads (GREEN)** - `a6f4c02` (feat)

_REFACTOR was a no-op: the change was purely additive and already ktlint-clean._

## Files Created/Modified
- `backend/.../domain/AgentSpan.kt` - Added provider, agentId, toolCallId, conversationId nullable fields with KDoc.
- `backend/.../convention/AgentSpanExtractor.kt` - Four `attrs.string(GenAiAttributes.*)` reads in the constructor call.
- `backend/.../test/.../SpanFixtures.kt` - Added the four gen_ai.* keys to the `genAiOnlyAttrs` golden fixture.
- `backend/.../test/.../AgentSpanExtractorGenAiOnlyTest.kt` - One @Test per field asserting populated values.
- `backend/.../test/.../AgentSpanExtractorNullableTest.kt` - Test asserting all four fields null on an empty-attrs span.

## Decisions Made
- Closed the gap by extending the code to the contract (not downgrading the doc), per the user's decided fix.
- Kept the four fields gen_ai.* only — no `unityinflow.*` `?:` fallback, since no such precedence applies.
- Preserved absence -> null (D-12); invented no default values.

## Deviations from Plan

None - plan executed exactly as written.

Note: the plan's verify commands used `cd backend && ./gradlew`, but the Gradle wrapper lives at the project root with `backend` as the `:backend` subproject. Verification was run as `./gradlew :backend:test` / `:backend:check` from the root — same tasks, same outcome. This is an invocation-path adjustment, not a behavioral deviation.

## Verification Results
- `./gradlew :backend:test` — BUILD SUCCESSFUL (all backend tests green, including 4 new populated-field tests and the 4 absent-field null assertions).
- `./gradlew :backend:check` — BUILD SUCCESSFUL (kotlinter/ktlint clean).
- `grep -c 'GenAiAttributes\.\(PROVIDER_NAME\|AGENT_ID\|TOOL_CALL_ID\|CONVERSATION_ID\)' AgentSpanExtractor.kt` → 4.
- RED gate confirmed before GREEN: `:backend:compileTestKotlin` failed with unresolved-reference errors for provider/agentId/toolCallId/conversationId.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Phase 01 (Attribute Convention + Domain Model) now has all four plans complete with no open gaps; WR-01 / UAT major gap closed.
- Ready for Phase 02 (ClickHouse Storage) — the AgentSpan typed surface is now complete for the §2 Recommended contract.

## Self-Check: PASSED

- All five modified files present on disk.
- Both task commits (`58e1ea9` RED, `a6f4c02` GREEN) present in git history.
- `./gradlew :backend:test` and `:backend:check` both green; extractor grep count = 4.

---
*Phase: 01-attribute-convention-domain-model*
*Completed: 2026-06-14*
