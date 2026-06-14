---
phase: 01-attribute-convention-domain-model
verified: 2026-06-14T00:00:00Z
status: human_needed
score: 4/4 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Decide whether the four published-but-dropped contract attributes (gen_ai.provider.name, gen_ai.agent.id, gen_ai.tool.call.id, gen_ai.conversation.id) belong in AgentSpan for v1.0, or whether the convention doc rows should be marked not-yet-extracted."
    expected: "Either AgentSpan gains provider/agentId/toolCallId/conversationId fields the extractor reads, OR docs/agent-span-convention.md §2 annotates those four rows as recognised-but-not-extracted-in-v1.0 so the contract does not over-promise."
    why_human: "The four keys are declared as Recommended in the published contract (docs/agent-span-convention.md §2) and have agent-semconv constants, but AgentSpanExtractor reads none of them and AgentSpan has no field for them — a KORE span carrying gen_ai.conversation.id is silently dropped at extraction. None of the four are named in any Phase 1 success criterion or in CONV-01/02/03, and no later ROADMAP phase references them, so this does not fail a goal criterion. But whether the published v1.0 contract should surface them is a product/contract decision (the artifact is a Stable contract KORE will depend on) — not a programmatically resolvable fact. Matches code-review finding WR-01."
---

# Phase 1: Attribute Convention & Domain Model Verification Report

**Phase Goal:** Any OTel-emitting agent source produces spans agent-tracer understands — the published attribute convention is the ecosystem's public contract, and the extractor turns raw OTLP attributes into typed agent semantics.
**Verified:** 2026-06-14T00:00:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

All four truths are the ROADMAP Phase 1 Success Criteria (the non-negotiable contract), merged with the plan-frontmatter must-haves.

| #   | Truth | Status | Evidence |
| --- | ----- | ------ | -------- |
| 1   | A developer can read a published, versioned convention document defining `unityinflow.skill.name`, `unityinflow.context.saturation_pct`, `unityinflow.cost.usd` alongside standard `gen_ai.*` token/model/agent attributes | ✓ VERIFIED | `docs/agent-span-convention.md` (190 lines) exists; all three unityinflow.* keys + both gen_ai.usage.* keys present; semver 1.0 + `unityinflow.schema_version` stamp; README links to it (1 match). |
| 2   | The convention document specifies the OTLP value type for every attribute and large-payload handling rules | ✓ VERIFIED | Doc contains "OTLP value type" column; 256 KB cap stated; `unityinflow.payload.truncated` + `unityinflow.payload.original_size_bytes` truncation keys present; per-attribute value-type rows verified. |
| 3   | A span carrying only standard `gen_ai.*` attributes (input/output tokens, invoke_agent/execute_tool operations) is extracted into typed agent fields with no emitter changes (CONV-02) | ✓ VERIFIED | `AgentSpanExtractorGenAiOnlyTest` (8 tests, all green): agentName from gen_ai.agent.name (and service.name fallback → "kore-runtime"), model from response→request, inputTokens=1234L/outputTokens=567L from gen_ai.usage.*, operation=INVOKE_AGENT, all unityinflow.* fields null. Tokens typed Long? (not stringified). |
| 4   | A span carrying `unityinflow.*` extensions is extracted into typed fields with precedence over generic fallbacks verified by tests (CONV-03) | ✓ VERIFIED | `AgentSpanExtractorPrecedenceTest` (3 green): skillName == unityinflow value "deep-research" over gen_ai.tool.name; unityinflow.outcome PARTIAL overrides OTel error status. `ClampTest`: saturation 137→100, -5→0, 73.5 preserved. `NullableTest`: missing→null no sentinel + extractor never throws on empty input. |

**Score:** 4/4 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
| -------- | -------- | ------ | ------- |
| `settings.gradle.kts` | Multi-module include | ✓ VERIFIED | `rootProject.name = "agent-tracer"`; `include(...)` of agent-semconv + backend. |
| `gradle/libs.versions.toml` | Version catalog pins | ✓ VERIFIED | kotlin 2.4.0, junit5 5.14.4, kotest 6.1.11, opentelemetry-proto 1.10.0-alpha all present. |
| `agent-semconv/.../GenAiAttributes.kt` | gen_ai.* key constants, single source | ✓ VERIFIED | 16 const vals incl. `gen_ai.usage.input_tokens`; only place gen_ai.* literals appear. |
| `agent-semconv/.../UnityInFlowAttributes.kt` | unityinflow.* key constants | ✓ VERIFIED | Contains `unityinflow.cost.usd` + 8 other extension keys. |
| `agent-semconv/.../GenAiOperation.kt` | Typed enum + fromOtel | ✓ VERIFIED | 7 variants; `fromOtel(String): GenAiOperation?` returns null on unknown, never throws. |
| `backend/.../domain/AgentSpan.kt` | Immutable typed model, nullable | ✓ VERIFIED | `data class AgentSpan` with 18 nullable typed fields (Long? tokens, Double? cost/saturation, Int? planStep), non-null outcome. |
| `backend/.../convention/AttrView.kt` | Type-preserving AnyValue coercion | ✓ VERIFIED | string/long/double/bool/int/stringList strict accessors over OTLP AnyValue; wrong variant → null. |
| `backend/.../convention/AgentSpanExtractor.kt` | Pure extractor, unityinflow.* precedence | ✓ VERIFIED | `fun extract`; priority-ordered lookup; uses agent-semconv constants (no raw literals); never throws. |
| `docs/agent-span-convention.md` | Published versioned contract | ✓ VERIFIED | 190 lines; all required keys, value-type column, payload rules, precedence, PII warning. |
| `README.md` | Links the convention | ✓ VERIFIED | Markdown link to `agent-span-convention.md` present. |

### Key Link Verification

| From | To | Via | Status | Details |
| ---- | -- | --- | ------ | ------- |
| `agent-semconv/build.gradle.kts` | `agenttracer.publishing` plugin | `id("agenttracer.publishing")` | ✓ WIRED | Plugin id applied; no `kore.publishing` remnant in buildSrc. |
| `AgentSpanExtractor.kt` | `agentsemconv` constants | import of GenAiAttributes/UnityInFlowAttributes/GenAiOperation | ✓ WIRED | 3 imports; constants used field-by-field; no raw key literals. |
| `AttrView.kt` | opentelemetry-proto KeyValue/AnyValue | reads AnyValue oneof per type | ✓ WIRED | Imports + has*/get* variant reads confirmed. |
| `backend/build.gradle.kts` | `:agent-semconv` + opentelemetry-proto | implementation deps | ✓ WIRED | `project(":agent-semconv")` + `libs.opentelemetry.proto`; agent-semconv itself has NO OTLP dep (boundary held). |
| `README.md` | `docs/agent-span-convention.md` | markdown link | ✓ WIRED | Link present. |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| Full multi-module suite passes (forced rerun) | `./gradlew test --rerun-tasks` | BUILD SUCCESSFUL; 43 tests across 9 classes, 0 failures, 0 errors | ✓ PASS |
| gen_ai-only span → typed fields | `AgentSpanExtractorGenAiOnlyTest` (8) | all green | ✓ PASS |
| unityinflow.* precedence | `AgentSpanExtractorPrecedenceTest` (3) | all green | ✓ PASS |
| saturation clamp [0,100] | `AgentSpanExtractorClampTest` (3) | all green | ✓ PASS |
| missing→null + never-throws | `AgentSpanExtractorNullableTest` (3) | all green | ✓ PASS |
| OTLP value-type preservation | `AgentSpanExtractorValueTypeTest` (7) | all green | ✓ PASS |
| operation enum + unknown→null | `AgentSpanExtractorOperationTest` (4) + `GenAiOperationTest` (4) | all green | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
| ----------- | ----------- | ----------- | ------ | -------- |
| CONV-01 | 01-01, 01-03 | Attribute convention published as documented contract (skill.name, saturation_pct, cost.usd + gen_ai.*) | ✓ SATISFIED | agent-semconv constants (machine surface) + docs/agent-span-convention.md v1.0 (prose contract); REQUIREMENTS.md marks Complete. |
| CONV-02 | 01-02 | Extractor reads standard OTel GenAI semconv (input/output tokens, invoke_agent/execute_tool) so any gen_ai-emitter renders without changes | ✓ SATISFIED | GenAiOnly test suite; extractor reads gen_ai.usage.* + gen_ai.operation.name via vendored constants. |
| CONV-03 | 01-02, 01-03 | Convention specifies OTLP value types per attribute + large-payload handling | ✓ SATISFIED | Doc value-type column + 256 KB cap + truncation keys; AttrView preserves types (ValueType test). |

All three phase requirement IDs (CONV-01, CONV-02, CONV-03) are declared in plan frontmatter, described in REQUIREMENTS.md, and accounted for. No orphaned requirements — REQUIREMENTS.md maps exactly CONV-01/02/03 to Phase 1.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
| ---- | ---- | ------- | -------- | ------ |
| (none) | — | No TODO/FIXME/XXX/TBD/PLACEHOLDER/HACK in main source | — | Clean |
| (none) | — | No `var`, no `!!` in main source | — | Conforms to CLAUDE.md Kotlin rules |

No stubs, no debt markers, no empty-return placeholders. The extractor is genuinely total over malformed input (NullableTest proves never-throws). Signing skips gracefully without env vars (observed in build log).

### Human Verification Required

#### 1. Four published contract attributes are dropped by the extractor (code-review WR-01)

**Test:** Decide whether `gen_ai.provider.name`, `gen_ai.agent.id`, `gen_ai.tool.call.id`, and `gen_ai.conversation.id` should be surfaced in `AgentSpan` for v1.0.
**Expected:** Either AgentSpan gains `provider`/`agentId`/`toolCallId`/`conversationId` fields that the extractor reads, OR `docs/agent-span-convention.md` §2 annotates those four rows as recognised-but-not-extracted-in-v1.0 — so the Stable contract does not over-promise.
**Why human:** All four keys are declared as **Recommended** in the published contract (doc §2 lines 58/64/66/67) and have agent-semconv constants, but `AgentSpanExtractor.extract` reads none of them and `AgentSpan` has no field for them — a KORE span carrying `gen_ai.conversation.id` (the session/thread id) is silently dropped at extraction with no warning. This is a contract-vs-implementation discrepancy on a Stable artifact KORE depends on. However: none of the four keys appear in any Phase 1 ROADMAP success criterion or in CONV-01/02/03, and no later ROADMAP phase references them — so this does **not** falsify any goal criterion. Whether the v1.0 contract *should* surface them is a product/contract decision, not a programmatically resolvable fact.

### Gaps Summary

**No goal-blocking gaps.** All 4 ROADMAP success criteria are observably true in the codebase and test-verified (43 tests green on a forced rerun). All 3 requirements (CONV-01/02/03) are satisfied. The build, version catalog, publishing plugin, agent-semconv constants module, AttrView coercion layer, AgentSpanExtractor, typed AgentSpan/AgentOutcome domain model, and the versioned convention doc all exist, are substantive, and are correctly wired (extractor → semconv constants, AttrView → OTLP proto, README → doc, build module boundaries held: agent-semconv has zero OTLP deps).

The single open item is the WR-01 contract-fidelity discrepancy: four Recommended attributes are published in the contract but silently dropped by the extractor. Because it touches the published v1.0 contract KORE will build against — yet contradicts no success criterion — it is surfaced as a human decision rather than an automatic gap. The other code-review findings (WR-02 protobuf pin not applied, WR-03 stringList empty-vs-null, WR-04 empty-string passthrough, WR-05 unused mockk / no coverage gate) are correctness/quality refinements that do not affect any Phase 1 goal criterion and are left to the advisory code review.

---

_Verified: 2026-06-14T00:00:00Z_
_Verifier: Claude (gsd-verifier)_
