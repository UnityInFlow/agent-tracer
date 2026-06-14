---
phase: 01-attribute-convention-domain-model
plan: 02
subsystem: domain
tags: [kotlin, otlp, gen-ai-semconv, extractor, domain-model, tdd]

# Dependency graph
requires:
  - "01-01: agent-semconv (GenAiAttributes/UnityInFlowAttributes/GenAiOperation) + backend Gradle module with opentelemetry-proto"
provides:
  - AgentSpan immutable typed domain model (nullable fields, no sentinels)
  - AgentOutcome enum with derive(otelStatus, override) factory
  - AttrView strict type-preserving coercion layer over OTLP KeyValue/AnyValue
  - AgentSpanExtractor pure function (OTLP attrs -> AgentSpan) with unityinflow.* precedence
  - SpanFixtures reusable golden gen_ai-only + full unityinflow.* OTLP fixtures
affects: [03-otlp-ingestion, 04-query-api-compose, 05-frontend]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Strict OTLP coercion: AttrView reads only the matching AnyValue oneof variant (has*/get*); wrong-variant or absent -> null (no lexical parse)"
    - "Priority-ordered field extraction: unityinflow.* key, then gen_ai.* key, then resource fallback, else null (D-11)"
    - "Pure extractor never throws on malformed input; nullable/sealed over exceptions (Security V5, T-01-03)"
    - "Single source of truth for operation: reuse agentsemconv.GenAiOperation instead of a duplicate domain enum"

key-files:
  created:
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentOutcome.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt
    - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorValueTypeTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorOperationTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorPrecedenceTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorClampTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt
    - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorPayloadTest.kt
  modified: []

key-decisions:
  - "Reuse agentsemconv.GenAiOperation as AgentSpan.operation type; AgentOperation.kt NOT created (plan permitted this and asked to record it)"
  - "extract() takes an extra otelStatusIsError: Boolean = false param (defaulted) so D-09 outcome derivation works without breaking the plan's extract(attrs, resource) call shape"
  - "Strict coercion (RESEARCH Q2): wrong AnyValue variant returns null, never lexically parses a string into a number"
  - "AttrView.int() narrows int64 to Int and rejects out-of-Int-range values (planStep is Int? per D-07)"

patterns-established:
  - "AttrView.has<Variant>()/get<Variant> reading over OTLP AnyValue is the canonical type-preserving accessor for all downstream OTLP consumers"
  - "Golden gen_ai-only fixture + full unityinflow.* fixture in SpanFixtures, reused across the extractor test suite"

requirements-completed: [CONV-02, CONV-03]

# Metrics
duration: 9min
completed: 2026-06-14
---

# Phase 01 Plan 02: Domain Model & Attribute Extractor Summary

**Pure-Kotlin OTLP-attributes -> typed `AgentSpan` extractor with a strict type-preserving `AttrView` coercion layer, `unityinflow.*`-over-`gen_ai.*` precedence, saturation clamping, null-on-absence, and verbatim payloads — the contract that gates Phase 2's typed ClickHouse columns.**

## Performance

- **Duration:** ~9 min
- **Started:** 2026-06-14T09:09:18Z
- **Completed:** 2026-06-14T09:18:26Z
- **Tasks:** 2
- **Files created:** 12

## Accomplishments

- `AgentSpan` immutable data class — 18 nullable typed fields (Long? tokens preserve OTLP int64; Double? cost/saturation; Int? planStep; payload/truncation contract represented) with `operation: GenAiOperation?` and a non-null `outcome: AgentOutcome`
- `AgentOutcome` enum + `derive(otelStatusIsError, outcomeOverride)` factory (OK->success, ERROR->failure, with `unityinflow.outcome` override; D-09)
- `AttrView` strict coercion over OTLP `KeyValue`/`AnyValue` — `string/long/double/bool/int/stringList` each read ONLY the matching `has*/get*` variant; wrong-variant or absent -> null (no silent lexical parse, RESEARCH Q2). Proto API confirmed by `javap` jar inspection.
- `AgentSpanExtractor.extract` — pure, no-I/O, priority-ordered (`unityinflow.*` then `gen_ai.*` then resource fallback, else null); saturation `coerceIn(0.0, 100.0)`; never throws
- `SpanFixtures` — OTLP `KeyValue` builders (string/int/double/bool/string-array) plus a golden gen_ai-only span and a full unityinflow.* span reused across all tests
- 7 extractor test classes, 32 tests; full multi-module suite green, `lintKotlin` clean
- TDD per task: ValueType test alongside Task 1 (AttrView), the six behavior tests alongside Task 2 (extractor)

## Task Commits

1. **Task 1: Domain model + AttrView coercion layer** - `7d0d2fa` (feat)
2. **Task 2: AgentSpanExtractor with precedence/clamp/nullable/payload** - `2bf9e7f` (feat)

**Plan metadata:** _(final docs commit — see git log)_

## Files Created

- `domain/AgentSpan.kt` - immutable typed model, nullable fields (D-12), Long? tokens (Pitfall 1)
- `domain/AgentOutcome.kt` - enum + derive()/fromOtel() factory (D-09)
- `convention/AttrView.kt` - strict type-preserving OTLP AnyValue accessors
- `convention/AgentSpanExtractor.kt` - pure priority-ordered extractor (D-11)
- `convention/SpanFixtures.kt` (test) - OTLP builders + golden/full fixtures
- `convention/AgentSpanExtractor{ValueType,GenAiOnly,Operation,Precedence,Clamp,Nullable,Payload}Test.kt` (test) - VALIDATION.md named patterns

## Decisions Made

- **GenAiOperation reuse:** `AgentSpan.operation` is typed as `agentsemconv.GenAiOperation` directly; no duplicate `domain/AgentOperation.kt` was created. The plan's Task 1 action explicitly permitted this ("prefer importing GenAiOperation directly ... delete this file and update AgentSpan's import — note the decision in the SUMMARY"). Single source of truth for the operation classification.
- **extract() signature:** added `otelStatusIsError: Boolean = false` as a third, defaulted parameter so the OTel-status-driven `AgentOutcome` (D-09) is derivable; the plan's `extract(attrs, resource)` call shape still works unchanged.
- **Strict coercion (Q2):** a wrong `AnyValue` variant yields null — no tolerant lexical fallback (e.g. `string_value` "1234" requested as `.long()` -> null). Matches RESEARCH Open Question Q2 RESOLVED.
- **AttrView.int():** narrows OTLP int64 to `Int?` for `planStep` (D-07) and returns null if the value is out of Int range, rather than silently truncating.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Reworded extractor KDoc to avoid raw-key-literal grep false positives**
- **Found during:** Task 2 verification
- **Issue:** Acceptance criterion `grep -rn 'gen_ai\.|unityinflow\.' AgentSpanExtractor.kt` requires no matches; KDoc prose contained bare `gen_ai.*`, `unityinflow.*`, `service.name`, and `unityinflow.outcome` tokens (prose references, not key literals) that tripped the grep — the same false-positive class the 01-01 plan hit.
- **Fix:** Reworded the KDoc to reference the constants by symbol (`[GenAiAttributes]`, `[UnityInFlowAttributes]`, `[GenAiAttributes.SERVICE_NAME]`, `[UnityInFlowAttributes.OUTCOME]`). The only remaining match is the package path `io.github.unityinflow.agenttracer.convention`, which is not a key literal. No quoted key literals exist (verified: `grep -nE '"(gen_ai|unityinflow)\.' ` returns none).
- **Files modified:** AgentSpanExtractor.kt
- **Verification:** `grep` excluding the package path returns none; `./gradlew :backend:test :backend:lintKotlin` green
- **Committed in:** 2bf9e7f (Task 2 commit)

### Plan artifact list note

The plan frontmatter `files_modified` listed `domain/AgentOperation.kt`; per the Task 1 action's own instruction this file was intentionally NOT created (GenAiOperation reused). 11 of the 12 listed paths were created; this is the documented decision above, not an omission.

**Total deviations:** 1 auto-fixed (bug, doc-only). **Impact:** none on behavior; satisfies the no-raw-literal acceptance criterion. No scope creep.

## Issues Encountered

- The `opentelemetry-proto-1.10.0-alpha.jar` was not yet materialized in the Gradle cache (only `.pom`/`.module`), so the API was confirmed via `javap` against the already-cached `1.4.0-alpha` jar — the `AnyValue`/`KeyValue`/`ArrayValue` accessor surface (`hasStringValue`/`getIntValue`/`getValuesList` etc.) is stable across these OTLP v1 versions. Compilation against 1.10.0-alpha succeeded.

## User Setup Required

None.

## Known Stubs

None. The extractor and domain model are fully functional and test-covered.

## Threat Flags

None. No new security surface beyond the threat_model already enumerated (T-01-03/05 mitigated by strict null-returning coercion + clamp + never-throw; T-01-04/06 are accept/defer for Phase 5 UI / Phase 2-3 persistence as planned).

## Next Phase Readiness

- The typed `AgentSpan` field set + per-attribute OTLP value types are the column contract for Phase 2's ClickHouse schema.
- `AttrView` plugs directly into the Phase 3 OTLP receiver: it consumes the parsed `List<KeyValue>` the receiver produces (`AttrView.of(span.attributesList)`), and `extract` takes the span's OTel status (`otelStatusIsError`).
- Convention doc (`docs/agent-span-convention.md`, CONV-01 prose contract) and README link remain for the phase's convention/documentation plan if not already covered — extraction logic (CONV-02/CONV-03) is complete and test-verified here.
- No blockers.

## Self-Check: PASSED

All 12 created files verified present on disk; both task commits (7d0d2fa, 2bf9e7f) verified in git history. Full backend suite: 32 tests, BUILD SUCCESSFUL; `lintKotlin` clean.

---
*Phase: 01-attribute-convention-domain-model*
*Completed: 2026-06-14*
