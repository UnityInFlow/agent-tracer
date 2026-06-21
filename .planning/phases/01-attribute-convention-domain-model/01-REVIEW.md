---
phase: 01-attribute-convention-domain-model
reviewed: 2026-06-21T00:00:00Z
depth: standard
files_reviewed: 24
files_reviewed_list:
  - agent-semconv/build.gradle.kts
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/UnityInFlowAttributes.kt
  - agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/AttributeKeysTest.kt
  - agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/GenAiOperationTest.kt
  - backend/build.gradle.kts
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentOutcome.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorClampTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorGenAiOnlyTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorNullableTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorOperationTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorPayloadTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorPrecedenceTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractorValueTypeTest.kt
  - backend/src/test/kotlin/io/github/unityinflow/agenttracer/convention/SpanFixtures.kt
  - buildSrc/build.gradle.kts
  - buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts
  - docs/agent-span-convention.md
  - gradle/libs.versions.toml
  - gradle/wrapper/gradle-wrapper.properties
findings:
  critical: 0
  warning: 3
  info: 7
  total: 10
status: issues_found
---

# Phase 01: Code Review Report — Full re-review (standard depth)

**Reviewed:** 2026-06-21
**Depth:** standard (per-file + cross-reference of extractor ↔ domain ↔ vendored constants ↔ published contract ↔ build config)
**Files Reviewed:** 24
**Status:** issues_found

## Findings Summary

| ID | Severity | Title | File |
|----|----------|-------|------|
| WR-02 | Warning | `embeddings` operation in contract has no enum variant — silently dropped to `null` | `agent-semconv/.../GenAiOperation.kt` |
| WR-03 | Warning | Aggregation `publicationName` embeds `-SNAPSHOT` version — Central Portal upload will be malformed | `build.gradle.kts:45` |
| WR-04 | Warning | `int()` silently nulls out-of-`Int`-range `plan.step` int64 values declared as int64 by the contract | `AttrView.kt:32` |
| IN-01 | Info | Published POM email `jiri@unityinflow.dev` does not match the author's address | `agenttracer.publishing.gradle.kts:81` |
| IN-02 | Info | `mockk` declared in version catalog but never used by any module | `gradle/libs.versions.toml:5,14` |
| IN-03 | Info | `protobuf-java` declared in version catalog but never depended on | `gradle/libs.versions.toml:7,18` |
| IN-04 | Info | `fromOtel` parsers are case-sensitive with no documented contract for casing | `GenAiOperation.kt:40`, `AgentOutcome.kt:50` |
| IN-05 | Info | `stringList` returns empty list (not `null`) for an empty array and silently drops non-string elements | `AttrView.kt:40-45` |
| IN-06 | Info | No coverage-gate (kover/jacoco) wired despite the >80% core-coverage release rule | `build.gradle.kts`, module build files |
| IN-07 | Info | Unsupported OTLP variants (`bytes_value`, `kvlist_value`, nested `array_value`) have no test asserting they map to `null` | `AttrView.kt`, value-type test |

> This is a full-phase standard-depth re-review of all 24 changed files. It
> re-evaluates the prior findings WR-02..WR-05 and IN-01..IN-06 recorded in the
> earlier review's "Prior findings status" section against the live code. WR-01
> (four dropped Recommended `gen_ai.*` keys) remains **RESOLVED** (verified: all
> four fields on `AgentSpan`, read via vendored constants in `AgentSpanExtractor`
> lines 41-42, 49-50, with positive + null tests). Prior WR-02 (protobuf pin
> unused) and WR-05 (stringList semantics) are re-scoped below; some prior IDs
> consolidated. New IDs continue the sequence.

## Summary

The domain model and extractor are well-constructed: `AgentSpan`/`AgentOutcome`
are immutable, `var`-free, `!!`-free, modelled with sealed-style enums, and the
extractor is a pure total function that delegates all type discrimination to
`AttrView` (which is strictly variant-gated and never throws — verified across
all six accessors). Null-on-absence (D-12), precedence (D-11), clamping (D-04),
and the four newly-added `gen_ai.*` ids are all test-covered. No Critical issues.

The remaining defects are a contract/code mismatch (`embeddings`), a release-time
packaging defect (`-SNAPSHOT` publication name), a silent-narrowing edge case,
and build-hygiene / metadata nits.

## Warnings

### WR-02: `embeddings` operation declared in the contract table has no enum variant — silently dropped

**File:** `agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt:9-50`
(cross-ref `docs/agent-span-convention.md:57`)
**Issue:** The published contract table (§2, line 57) enumerates the
`gen_ai.operation.name` values as including **`embeddings`**:
`"...Enum incl. chat, plan, execute_tool, invoke_agent, generate_content, text_completion, embeddings."`
`gen_ai.operation.name` is the only **Required** attribute in the whole contract.
But `GenAiOperation` (lines 11-29) defines no `EMBEDDINGS` variant, so
`GenAiOperation.fromOtel("embeddings")` hits the `else -> null` branch (line 49)
and the operation is silently dropped to `null`. A span the contract explicitly
permits loses its (Required) operation classification — the same
"published-but-dropped" class of defect as the now-fixed WR-01. The doc's §5
"operations that matter for v1.0" list (line 137) omits embeddings, so the two
parts of the contract document also disagree with each other.
**Fix:** Pick one and make code and doc agree. Either (a) add the variant:
```kotlin
/** `embeddings` — an embeddings-generation call. */
EMBEDDINGS,
```
and map it in `fromOtel` (`"embeddings" -> EMBEDDINGS`) plus add a
`GenAiOperationTest` case; or (b) if embeddings is intentionally out of v1 node
classification, remove `embeddings` from the §2 enum list in
`docs/agent-span-convention.md:57` so the contract no longer promises it. Option
(a) is preferred — dropping a Required-attribute value is the exact failure mode
this phase exists to prevent.

### WR-03: Aggregation `publicationName` embeds the `-SNAPSHOT` version — Central Portal bundle is malformed

**File:** `build.gradle.kts:45` (cross-ref `:21` where `version = "0.0.1-SNAPSHOT"`)
**Issue:** `publicationName = "agent-tracer-${project.version}"` interpolates the
subproject-wide `version = "0.0.1-SNAPSHOT"` (line 21), producing
`agent-tracer-0.0.1-SNAPSHOT`. The Sonatype Central Portal does **not** accept
`-SNAPSHOT` artifacts — a real release upload must use a final version (e.g.
`0.0.1`). Because the version string is the value flipped at release time, the
publication name silently inherits `-SNAPSHOT` and will only surface as a failed
upload during the release itself (the aggregation block "is not exercised until
release", per the comment on line 38), i.e. at the worst possible moment. The
prior review flagged this as the "SNAPSHOT publication name" item; it is still
live.
**Fix:** Either decouple the publication name from the snapshot suffix, or gate
the release on a non-snapshot version. Minimal fix — strip the suffix:
```kotlin
publicationName = "agent-tracer-${project.version.toString().removeSuffix("-SNAPSHOT")}"
```
Better: add a release-time guard so a snapshot version can never reach the portal:
```kotlin
val releaseVersion = project.version.toString()
require(!releaseVersion.endsWith("-SNAPSHOT")) {
    "Refusing to aggregate a SNAPSHOT version ($releaseVersion) for Central Portal."
}
publicationName = "agent-tracer-$releaseVersion"
```

### WR-04: `int()` silently nulls out-of-`Int`-range `plan.step` values the contract declares as int64

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt:32`
(cross-ref `AgentSpanExtractor.kt:59`, `docs/agent-span-convention.md:84`)
**Issue:** The contract declares `unityinflow.plan.step` as **`int_value` (int64)**
(doc line 84), and `AgentSpan.planStep` is `Int?` (line 39), read via
`attrs.int(...)`. `AttrView.int()` (line 32) narrows the int64 to `Int` and
returns `null` for any value outside `Int.MIN_VALUE..Int.MAX_VALUE`. So an
in-spec int64 plan step `> 2_147_483_647` is **indistinguishable from "absent"** —
a present, valid contract value is silently discarded with no signal. The
value-type test (`AgentSpanExtractorValueTypeTest.kt:63-64`) actually *codifies*
this (`Long.MAX_VALUE` → null) as if it were desirable, but for a real span it is
data loss masquerading as absence. Plan-step ordinals realistically fit in `Int`,
so impact is low, but the type contract (int64) and storage type (Int) are
mismatched and the failure mode is silent.
**Fix:** Make the domain field match the contract's declared int64 type — change
`AgentSpan.planStep` to `Long?` and read it with `attrs.long(...)` instead of
`attrs.int(...)`:
```kotlin
// AgentSpan.kt
val planStep: Long?,
// AgentSpanExtractor.kt
planStep = attrs.long(UnityInFlowAttributes.PLAN_STEP),
```
If `Int` is a deliberate storage choice, document in the convention that
`plan.step` is effectively int32 and add a test asserting the truncation is
intended; do not leave int64→null as an untested-rationale silent path.

## Info

### IN-01: Published POM developer email does not match the author's address

**File:** `buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts:81`
**Issue:** The POM hardcodes `email.set("jiri@unityinflow.dev")`. The ecosystem
author's address on record is `hermann.jirka@gmail.com`, and `unityinflow.dev` is
not a domain referenced anywhere else (the org/SCM/URL fields all use
`github.com/UnityInFlow`). For a Maven Central artifact this is permanent public
metadata; if `jiri@unityinflow.dev` is not a real, monitored mailbox, downstream
consumers and Sonatype have no working contact.
**Fix:** Set the email to a real, monitored address (e.g.
`hermann.jirka@gmail.com`) or confirm `jiri@unityinflow.dev` is live and intended.

### IN-02: `mockk` declared in the version catalog but never used

**File:** `gradle/libs.versions.toml:5,14`
**Issue:** `mockk = "1.14.11"` and the `mockk` library alias are declared, but no
module references `libs.mockk` (the extractor and `AttrView` are pure functions
tested with real OTLP builders, so no mocking is needed). Dead catalog entries
drift out of date and imply a dependency the build does not have.
**Fix:** Remove the `mockk` version + library entries from `libs.versions.toml`
until a module actually needs mocking, or add a comment noting it is reserved for
the Phase 3 ingest pipeline.

### IN-03: `protobuf-java` declared in the version catalog but never depended on

**File:** `gradle/libs.versions.toml:7,18`
**Issue:** `protobuf-java = "4.35.1"` and its library alias are declared but never
referenced by any `build.gradle.kts`. `opentelemetry-proto` pulls protobuf-java
transitively; pinning it explicitly only takes effect if a module declares it (or
a constraint/platform forces it). As written it is an inert entry that gives a
false sense the protobuf runtime version is enforced.
**Fix:** If the intent is to pin the transitive protobuf-java to 4.35.1, add an
explicit constraint in `backend/build.gradle.kts`
(`constraints { implementation(libs.protobuf.java) }`); otherwise remove the
catalog entries.

### IN-04: `fromOtel` parsers are case-sensitive with no documented casing contract

**File:** `agent-semconv/.../GenAiOperation.kt:40-50`,
`backend/.../domain/AgentOutcome.kt:50-57`
**Issue:** Both `fromOtel` functions match exact lowercase literals
(`"invoke_agent"`, `"success"`, …) and return `null` for anything else, including
`"INVOKE_AGENT"` or `"Success"`. The OTel GenAI semconv values are lowercase by
spec and `unityinflow.outcome` is documented lowercase (doc line 85), so this is
acceptable — but the case-sensitivity is an undocumented assumption. A
mixed-case emitter would have its (valid-looking) operation/outcome silently
nulled.
**Fix:** Add a one-line KDoc note on each `fromOtel` that matching is
case-sensitive against the lowercase contract values, and add a negative test
(e.g. `fromOtel("INVOKE_AGENT").shouldBeNull()`) so the contract is pinned. Do
**not** lowercase-normalize silently unless the convention says values are
case-insensitive.

### IN-05: `stringList` returns an empty list for an empty array and silently drops non-string elements

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt:40-45`
**Issue:** Two undocumented edge behaviours: (1) an `array_value` with zero
elements returns `emptyList()`, not `null`, so a caller cannot distinguish
"absent" from "present but empty" the way every other accessor's null contract
implies; (2) non-`string_value` elements inside the array are silently filtered
out (line 43), so a mixed array `["stop", 5]` yields `["stop"]` with no signal of
the dropped element. Neither is covered by a test
(`AgentSpanExtractorValueTypeTest` only exercises the all-string happy path).
**Fix:** Document both behaviours in the `stringList` KDoc (empty array → empty
list; non-string elements dropped) and add tests asserting each — an empty-array
case and a mixed-type-array case — so the semantics are pinned, not incidental.

### IN-06: No coverage gate wired despite the >80% core-coverage release rule

**File:** `build.gradle.kts` (root), `backend/build.gradle.kts`,
`agent-semconv/build.gradle.kts`
**Issue:** CLAUDE.md mandates ">80% test coverage on core logic before release",
but no coverage tooling (kover/jacoco) is applied or thresholded in any build
file. Coverage is currently asserted only by manual test-count inspection in the
verification report. Without a gate, a future change that drops a branch (e.g. a
new `AttrView` accessor with no test) ships unnoticed.
**Fix:** Apply `org.jetbrains.kotlinx.kover` at the root and set a verification
rule (e.g. `minBound(80)`) on `agent-semconv` + `backend`, wired into the CI
`check` task. Out of strict phase-01 scope but a release blocker per the
ecosystem rule.

### IN-07: Unsupported OTLP variants have no test pinning their null mapping

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt`,
`AgentSpanExtractorValueTypeTest.kt`
**Issue:** `AttrView` correctly handles only `string`/`int`/`double`/`bool`/string
`array` variants. OTLP also defines `bytes_value`, `kvlist_value`, and nested
non-string `array_value`. The code's behaviour for these (return `null` / empty)
is correct-by-construction (all accessors are `hasXxxValue()`-gated), but there is
no test asserting that, e.g., a `bytes_value` carried under a string key yields
`null` rather than throwing. The "never throws on malformed input" guarantee
(Security V5 / DoS, cited in `AttrView.kt:14`) is thus only partially pinned.
**Fix:** Add a test sending a `bytes_value` and a `kvlist_value` through
`AttrView` and asserting every accessor returns `null` without throwing, locking
in the DoS-safety contract for the unsupported variants.

---

## Prior findings status (earlier full-phase review)

- **WR-01** (four dropped Recommended `gen_ai.*` keys) — **RESOLVED.** All four
  fields present on `AgentSpan` (lines 17, 19, 25, 27), read via vendored
  constants in `AgentSpanExtractor` (lines 41-42, 49-50), positive + null tests
  green.
- **`embeddings` enum gap** — still open → carried as **WR-02** with concrete
  fix.
- **SNAPSHOT publication name** — still open → carried as **WR-03**.
- **`int()` narrowing** — still open → carried as **WR-04**.
- **POM email** — still open → carried as **IN-01**.
- **unused `mockk`** — still open → carried as **IN-02**.
- **protobuf pin (prior WR-02)** — re-scoped: the pin is inert (never depended
  on) → carried as **IN-03** (downgraded; it is build hygiene, not a correctness
  risk).
- **`fromOtel` case-sensitivity** — still open → carried as **IN-04**.
- **`stringList` / empty-string AttrView semantics (prior WR-05)** — re-scoped:
  the empty-string concern is moot (`string()` returns the empty string verbatim,
  which is correct); the live gap is empty-array + non-string-element semantics →
  carried as **IN-05** (downgraded to Info — documentation/test gap, not a bug).
- **coverage gate** — still open → carried as **IN-06**.
- **unsupported OTLP variants** — still open → carried as **IN-07**.

---

_Reviewed: 2026-06-21_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
