---
phase: 01-attribute-convention-domain-model
reviewed: 2026-06-14T00:00:00Z
depth: standard
files_reviewed: 13
files_reviewed_list:
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/UnityInFlowAttributes.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentOutcome.kt
  - backend/src/main/kotlin/io/github/unityinflow/agenttracer/domain/AgentSpan.kt
  - build.gradle.kts
  - buildSrc/build.gradle.kts
  - buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts
  - gradle/libs.versions.toml
  - settings.gradle.kts
  - docs/agent-span-convention.md
findings:
  critical: 0
  warning: 5
  info: 6
  total: 11
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-06-14T00:00:00Z
**Depth:** standard
**Files Reviewed:** 13
**Status:** issues_found

## Summary

Phase 1 delivers the agent-span attribute convention: vendored `gen_ai.*` /
`unityinflow.*` key constants (`agent-semconv`), a type-preserving OTLP
`AttrView`, and a pure `AgentSpanExtractor` mapping into a typed immutable
`AgentSpan`. The code is clean, idiomatic Kotlin: no `var`, no `!!`, sealed-style
enums for domain modelling, immutable data classes, and the never-throws/absence-is-
information contract is consistently honoured. ktlint (via the kotlinter plugin) IS
wired into the build and runs as part of `check` on both modules — verified.

No BLOCKER-class defects (no injection, no secrets, no crash paths — the extractor
is genuinely total over malformed input). However there are several correctness and
contract-fidelity gaps that should be fixed before this convention is published as a
"Stable contract" artifact that KORE will depend on, plus build-config defects where
declared intent (protobuf pin, currency/coverage notes) is silently not enforced.

The most material findings: (1) `gen_ai.provider.name`, `gen_ai.agent.id`,
`gen_ai.tool.call.id`, and `gen_ai.conversation.id` are declared in the contract and
have key constants but are silently dropped by the extractor — they never reach
`AgentSpan`; (2) the `protobuf-java:4.35.1` pin the STACK doc calls out as required
is declared in the version catalog but never applied, so the runtime resolves the
older transitive `4.34.0`; (3) `stringList` collapses "present but empty/all-non-
string array" into a non-null empty list while the rest of the model treats absence
as null, an inconsistency that will surface as `[]` vs `null` ambiguity downstream.

## Warnings

### WR-01: Contract attributes silently dropped by the extractor (provider, agent.id, tool.call.id, conversation.id)

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AgentSpanExtractor.kt:37-72`
**Issue:** `GenAiAttributes` defines `PROVIDER_NAME`, `AGENT_ID`, `TOOL_CALL_ID`,
and `CONVERSATION_ID`, and the convention table (`docs/agent-span-convention.md`
§2) lists all four as Recommended attributes that emitters will send. But
`AgentSpanExtractor.extract` reads none of them, and `AgentSpan` has no fields for
them. They are parsed into existence as constants, documented as contract, then
thrown away. For a "Stable contract" artifact, that is a silent data-loss path: a
KORE span carrying `gen_ai.conversation.id` (the session/thread id called out in §2)
or `gen_ai.provider.name` will have that information dropped at extraction with no
warning. Either the fields belong in `AgentSpan` or the constants/contract rows
should be marked as not-yet-extracted in v1.0.
**Fix:** Add the missing fields and read them, e.g.:
```kotlin
data class AgentSpan(
    // ...
    val provider: String?,
    val agentId: String?,
    val toolCallId: String?,
    val conversationId: String?,
    // ...
)

// in extract():
provider = attrs.string(GenAiAttributes.PROVIDER_NAME),
agentId = attrs.string(GenAiAttributes.AGENT_ID),
toolCallId = attrs.string(GenAiAttributes.TOOL_CALL_ID),
conversationId = attrs.string(GenAiAttributes.CONVERSATION_ID),
```
If they are intentionally deferred, add a one-line note in the convention doc and a
KDoc comment so the omission is a documented decision rather than a silent gap.

### WR-02: Declared protobuf-java pin is never applied — runtime uses the older transitive version

**File:** `gradle/libs.versions.toml:7,18` (and absent from `backend/build.gradle.kts`)
**Issue:** The version catalog declares `protobuf-java = "4.35.1"` with a matching
`libraries` entry, and the STACK doc explicitly states "pin protobuf-java 4.35.1
explicitly". No module references `libs.protobuf.java`, so the pin is dead config.
`./gradlew backend:dependencies` resolves `com.google.protobuf:protobuf-java:4.34.0`
(opentelemetry-proto's transitive version) on the runtime classpath. The intended
pin is silently a no-op. This is a maintainability/supply-chain hazard: a reader
trusts the catalog as the source of truth for the protobuf version, but it lies.
**Fix:** Either apply the pin where it is meant to take effect:
```kotlin
// backend/build.gradle.kts
dependencies {
    implementation(libs.opentelemetry.proto)
    implementation(libs.protobuf.java) // enforce the 4.35.1 pin
    // ...
}
```
or, if the transitive 4.34.0 is acceptable, delete the `protobuf-java` version and
library entries from the catalog so the declared intent matches reality.

### WR-03: `stringList` returns non-null empty list for a present-but-empty array, breaking the absence-is-null model

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt:40-45`
**Issue:** The whole model is "absence is information, every field nullable with no
sentinel" (D-12). `stringList` returns `null` only when the key is absent or not an
array. But if the array is present and empty, OR present with only non-string
elements (all filtered out), it returns a non-null empty `List<String>`. So
`finishReasons` can be `null` (absent) OR `[]` (present-but-empty) OR
`[]` (present-but-all-ints) — three OTLP states collapse into two
indistinguishable representations, and the "present but unusable" case is reported
as a usable empty list rather than as absence. This contradicts the §3 rule
"Absence is information" and will force every downstream aggregation to special-case
empty-vs-null.
**Fix:** Decide and document one rule. If empty/unusable should be treated as
absence:
```kotlin
fun stringList(key: String): List<String>? {
    val value = attributes[key]?.takeIf { it.hasArrayValue() } ?: return null
    val strings = value.arrayValue.valuesList
        .filter { it.hasStringValue() }
        .map { it.stringValue }
    return strings.ifEmpty { null }
}
```
Add a test for the present-but-empty array and the present-but-all-non-string array
cases — neither is currently covered by `AgentSpanExtractorValueTypeTest`.

### WR-04: Empty-string and whitespace-only string values pass through as meaningful identities

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt:20`; consumed at `AgentSpanExtractor.kt:38-46`
**Issue:** `string()` returns any `string_value` including `""`. An emitter that
sends `gen_ai.agent.name = ""` (empty string) defeats the `service.name` fallback at
`AgentSpanExtractor.kt:39-40`: the `?:` only fires on `null`, not on `""`, so the
agent gets an empty-string identity instead of falling back to `kore-runtime`. Same
hazard for `model`, `skillName` (an empty `skill.name` blocks the `tool.name`
fallback), and `outcome` (an empty `unityinflow.outcome` is harmless since `fromOtel`
returns null, but an emitter sending `"  "` would also fall through silently). Empty
strings are a common emitter bug; treating them as present values undermines every
precedence/fallback chain.
**Fix:** Treat blank strings as absent at the view boundary so all fallbacks behave:
```kotlin
fun string(key: String): String? =
    attributes[key]?.takeIf { it.hasStringValue() }?.stringValue?.takeIf { it.isNotBlank() }
```
or apply `.takeIf { it.isNotBlank() }` at each identity site. Add tests for empty
`agent.name` falling back to `service.name` and empty `skill.name` falling back to
`tool.name`.

### WR-05: `mockk` declared in the catalog but unused; >80% coverage requirement not enforced in the build

**File:** `gradle/libs.versions.toml:5,14`; `backend/build.gradle.kts`, `agent-semconv/build.gradle.kts`
**Issue:** Two related quality-gate gaps. (1) `mockk` is declared in the version
catalog but no module declares it as a dependency — dead config that implies a
testing approach not in use. (2) The ecosystem CLAUDE.md mandates ">80% test
coverage on core logic before release", but no coverage tool (Kover/JaCoCo) is
applied to either module and `check` has no coverage verification task. The
"coverage > 80%" constraint is therefore unenforced and unmeasured — it can silently
regress. The extractor and AttrView are the "core logic" the rule targets.
**Fix:** Remove the unused `mockk` catalog entries, OR wire it where intended. Add a
coverage plugin (e.g. `org.jetbrains.kotlinx.kover`) to the convention/subprojects
block with a `koverVerify` rule of 80% bound into `check`, so the release gate is
machine-checked rather than aspirational.

## Info

### IN-01: Developer email inconsistent across project metadata

**File:** `buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts:81`
**Issue:** The published POM hardcodes `email.set("jiri@unityinflow.dev")`, while the
environment/profile records the author email as `hermann.jirka@gmail.com`. For a
public Maven Central artifact this is a permanent, immutable metadata field. Confirm
which address is canonical before the artifact is released (it cannot be changed
after publish).
**Fix:** Set the intended public contact email; if `jiri@unityinflow.dev` is the
deliberate org address, no change needed — just confirm it resolves.

### IN-02: `bytes_value` and `kvlist_value` OTLP variants are silently unsupported with no documentation

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt:19-45`
**Issue:** The convention doc (§1) enumerates all seven `AnyValue` variants
including `kvlist_value` and `bytes_value`, but `AttrView` exposes accessors only for
string/int/double/bool/string-array. An attribute sent as `kvlist_value` or
`bytes_value` is simply never readable. This is acceptable for v1.0 (no contract
field uses those variants), but it is undocumented — a reader cannot tell whether the
omission is intentional. Note also `stringList` ignores `int_value`/`double_value`
array elements (it filters to `hasStringValue()` only), which is correct for the
single string[] field but worth stating.
**Fix:** Add a KDoc line on `AttrView` noting `kvlist_value`/`bytes_value` are
intentionally unsupported in v1.0 because no convention attribute uses them.

### IN-03: Convention enumerates `embeddings` operation but the enum omits it

**File:** `agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt:9-30`; `docs/agent-span-convention.md:57`
**Issue:** The contract table (§2, `gen_ai.operation.name` row) lists `embeddings` as
part of the enum, but `GenAiOperation` does not model it, so `fromOtel("embeddings")`
returns `null` and an embeddings span is unclassified. §5 does say only a subset
"matter for v1.0", so this is likely intentional — but the doc's enum list and the
code's enum disagree, which is confusing for an emitter author reading the contract.
**Fix:** Either add `EMBEDDINGS` to the enum, or annotate the doc row to say
`embeddings` is recognised in the OTel enum but intentionally unclassified
(→ null operation) in agent-tracer v1.0.

### IN-04: `fromOtel` outcome/operation parsing is case- and whitespace-sensitive with no normalization

**File:** `agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt:40-50`; `backend/.../domain/AgentOutcome.kt:50-57`
**Issue:** Both `fromOtel` implementations do exact string matching. `"Invoke_Agent"`,
`"INVOKE_AGENT"`, or `" invoke_agent"` all return `null`. OTLP values are lowercase by
the GenAI convention, so this is contract-correct, but real emitters drift. Since the
contract explicitly chose "absence is information / never throws", a mistyped-case
value silently becomes an unclassified node rather than a logged hint. Low severity —
documented design — but worth a deliberate decision.
**Fix:** Optionally normalize with `value.trim().lowercase()` before matching, or
leave strict and document that operation/outcome values are matched verbatim
(case-sensitive) per the OTel convention.

### IN-05: `int()` out-of-range int64 silently maps to null, losing the value distinct from absence

**File:** `backend/src/main/kotlin/io/github/unityinflow/agenttracer/convention/AttrView.kt:32`
**Issue:** `int()` returns `null` for an int64 outside Int range. For `plan.step`
(the only `Int?` field) this is fine, but the result is indistinguishable from "absent"
— a plan step of `5_000_000_000` and a missing plan step both become `null`. Tested
and intentional, but if `plan.step` is ever expected to exceed Int range the model
silently drops it. Consider whether `planStep` should be `Long?` to match the OTLP
int64 source type and avoid the lossy narrowing.
**Fix:** No change required for v1.0; consider `Long?` for `planStep` to remove the
narrowing branch entirely, since the OTLP source is int64.

### IN-06: `publicationName` in nmcp aggregation embeds a SNAPSHOT version

**File:** `build.gradle.kts:46` (`publicationName = "agent-tracer-${project.version}"`, version = `0.0.1-SNAPSHOT`)
**Issue:** The Sonatype Central Portal bundle name interpolates `project.version`,
currently `0.0.1-SNAPSHOT`. Central Portal does not accept SNAPSHOT releases, so this
is harmless until release, but the publication name will read `agent-tracer-0.0.1-SNAPSHOT`
unless the version is bumped first. The aggregation block is noted as "not exercised
until release", so this is a reminder, not a live defect.
**Fix:** Ensure the release process bumps `version` away from `-SNAPSHOT` before
running the aggregation upload; consider failing the upload task if the version
contains `SNAPSHOT`.

---

_Reviewed: 2026-06-14T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
