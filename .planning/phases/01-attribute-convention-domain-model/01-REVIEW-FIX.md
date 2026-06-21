---
phase: 01-attribute-convention-domain-model
fixed_at: 2026-06-21T16:30:18Z
review_path: .planning/phases/01-attribute-convention-domain-model/01-REVIEW.md
iteration: 1
findings_in_scope: 3
fixed: 3
skipped: 0
status: all_fixed
---

# Phase 01: Code Review Fix Report

**Fixed at:** 2026-06-21T16:30:18Z
**Source review:** .planning/phases/01-attribute-convention-domain-model/01-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 3 (Warnings WR-02, WR-03, WR-04 — Info skipped this pass)
- Fixed: 3
- Skipped: 0

**Verification:** `./gradlew :backend:test :agent-semconv:test :backend:check :agent-semconv:check` — BUILD SUCCESSFUL (all tests + ktlint GREEN).

## Fixed Issues

### WR-02: `embeddings` operation declared in the contract has no enum variant — silently dropped

**Files modified:** `agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt`, `agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/GenAiOperationTest.kt`, `docs/agent-span-convention.md`
**Commit:** 328e560
**Applied fix:** Per the established "extend the code to match the contract" decision (WR-01 closure), added an `EMBEDDINGS` enum variant to `GenAiOperation` and mapped `"embeddings" -> EMBEDDINGS` in `fromOtel`, so the contract's enumerated `embeddings` value is no longer silently dropped to `null`. Added a `GenAiOperationTest` case asserting `fromOtel("embeddings") == EMBEDDINGS`. Reconciled the doc's §5 ("operations that matter for v1.0") to include `embeddings` as an LLM-call node, removing the §2/§5 contradiction in the contract's favour (kept the contract's intent — embeddings is permitted).

### WR-04: `int()` silently nulls out-of-`Int`-range `plan.step` int64 values

**Files modified:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt`, `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt`, `backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorValueTypeTest.kt`
**Commit:** 4bcd482
**Applied fix:** Changed `AgentSpan.planStep` from `Int?` to `Long?` to match the contract's declared `int_value` (int64) type, and switched the extractor to read it via `attrs.long(UnityInFlowAttributes.PLAN_STEP)` instead of `attrs.int(...)`. (`AttrView.long()` already existed and preserves full int64 range while accepting `int_value`, so no new accessor was needed.) Replaced the value-type test that codified the lossy Int truncation (`Long.MAX_VALUE` → null) with a test asserting `plan.step` now round-trips `Long.MAX_VALUE` via `long()`. Kept the generic `int()`-narrowing test but decoupled it from `PLAN_STEP` (it now uses a generic test key), since `int()` remains a valid `AttrView` utility no longer used for plan-step.

### WR-03: Aggregation `publicationName` embeds the `-SNAPSHOT` version — Central Portal bundle is malformed

**Files modified:** `build.gradle.kts`
**Commit:** 91da630
**Applied fix:** Changed line 45 from `publicationName = "agent-tracer-${project.version}"` to `publicationName = "agent-tracer-${project.version.toString().removeSuffix("-SNAPSHOT")}"`, so the base artifact name no longer embeds the `-SNAPSHOT` suffix when the subproject `version` is a pre-release snapshot. The root build script evaluates cleanly (`./gradlew help` GREEN).

---

_Fixed: 2026-06-21T16:30:18Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
