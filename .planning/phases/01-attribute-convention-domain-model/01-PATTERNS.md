# Phase 1: Attribute Convention & Domain Model - Pattern Map

**Mapped:** 2026-06-13
**Files analyzed:** 13 (new — this is the project's first source code)
**Analogs found:** 11 / 13 (strong analogs in sibling repo `08-kore-runtime`)

## Greenfield Note

The agent-tracer repo (`09-agent-tracer/`) has **NO source code yet** — root contains only `.planning/`, `CLAUDE.md`, `09-agent-tracer.md`, and the harness guide. There are no `.kt`, `.gradle.kts`, or version-catalog files in this repo. CONTEXT.md confirms: "greenfield repo, no source code yet."

**However, real analogs exist in sibling ecosystem repos** under the same parent dir (`unity-in-flow-ai/`). `08-kore-runtime` (Tool 08, same Phase 3, `io.github.unityinflow` group) is a **multi-module Gradle + version catalog + Maven Central publishing** project — a near-exact structural template for this phase's `agent-semconv` + `backend` split. `05-budget-breaker` and `06-token-dashboard` are secondary Kotlin/Gradle references. The planner should copy build scaffolding and Kotlin house-style directly from `kore-runtime` rather than inventing conventions, with documented deviations (group artifact names, OTLP-proto dep instead of LLM deps, Gradle 9.5.1 vs 9.4.1).

All `kore-runtime` paths below are **absolute** and live OUTSIDE this repo. The planner reads them as reference; it must not import or symlink them — it copies the *patterns*.

## File Classification

| New File (in 09-agent-tracer) | Role | Data Flow | Closest Analog | Match Quality |
|-------------------------------|------|-----------|----------------|---------------|
| `settings.gradle.kts` | config (build) | n/a | `08-kore-runtime/settings.gradle.kts` | exact (multi-module include) |
| `gradle/libs.versions.toml` | config (version catalog) | n/a | `08-kore-runtime/gradle/libs.versions.toml` | exact (structure), adapt deps |
| `build.gradle.kts` (root) | config (build) | n/a | `08-kore-runtime/build.gradle.kts` | exact (subprojects + nmcp aggregation) |
| `buildSrc/build.gradle.kts` | config (build) | n/a | `08-kore-runtime/buildSrc/build.gradle.kts` | exact |
| `buildSrc/src/main/kotlin/*.publishing.gradle.kts` | config (convention plugin) | n/a | `08-kore-runtime/buildSrc/src/main/kotlin/kore.publishing.gradle.kts` | exact (rename kore→agenttracer) |
| `agent-semconv/build.gradle.kts` | config (module build) | n/a | `08-kore-runtime/kore-core/build.gradle.kts` | role-match (pure module, minimal deps) |
| `backend/build.gradle.kts` | config (module build) | n/a | `08-kore-runtime/kore-core/build.gradle.kts` | exact (lib module w/ JUnit5+Kotest) |
| `agent-semconv/.../GenAiAttributes.kt` | constants/utility | transform (key registry) | none — greenfield, establish convention | none |
| `agent-semconv/.../UnityInFlowAttributes.kt` | constants/utility | transform (key registry) | none — greenfield, establish convention | none |
| `agent-semconv/.../GenAiOperation.kt` | model (enum) | transform | `08-kore-runtime/.../AgentResult.kt` (sealed/enum idiom) | role-match (modelling idiom only) |
| `backend/.../domain/AgentSpan.kt` (+ `AgentOutcome`, `AgentOperation`) | model (data class + sealed) | transform | `08-kore-runtime/.../TokenUsage.kt` + `AgentResult.kt` | exact (data class + sealed idiom) |
| `backend/.../convention/AgentSpanExtractor.kt` | service/utility (pure fn) | transform (OTLP attrs → typed model) | none — greenfield, establish convention | none (design in RESEARCH §Code Examples) |
| `backend/.../convention/AttrView` coercion helpers | utility (pure fn) | transform (OTLP AnyValue → Kotlin type) | none — greenfield, establish convention | none |
| `backend/src/test/.../*Test.kt` (extractor/precedence/clamp/payload) | test | transform | `08-kore-runtime/.../AgentResultTest.kt` | exact (JUnit5 + Kotest style) |
| `docs/agent-span-convention.md` | documentation (contract) | n/a | none — greenfield, see RESEARCH §Convention Document Contents | none |

## Pattern Assignments

### `settings.gradle.kts` (build config)

**Analog:** `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/settings.gradle.kts`

**Pattern** (lines 1-15) — `rootProject.name` + `include(...)` listing every module:
```kotlin
rootProject.name = "kore"

include(
    "kore-core",
    "kore-mcp",
    // ...
)
```
**Apply to agent-tracer:** `rootProject.name = "agent-tracer"`; `include("agent-semconv", "backend")` (the two-module split from RESEARCH §Recommended Project Structure / D-03). Do NOT add frontend modules this phase.

---

### `gradle/libs.versions.toml` (version catalog)

**Analog:** `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/gradle/libs.versions.toml`

**Structure to copy** — `[versions]` / `[libraries]` / `[plugins]` sections, `version.ref` indirection, plugin aliases for `kotlin-jvm`, `kotlin-serialization`, `kotlinter`, `nmcp`:
```toml
[versions]
kotlin = "2.3.0"
junit5 = "5.12.0"
kotest = "6.1.11"
mockk = "1.14.0"
kotlinter = "5.0.0"
nmcp = "1.4.4"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlinter = { id = "org.jmailen.kotlinter", version.ref = "kotlinter" }
nmcp = { id = "com.gradleup.nmcp", version.ref = "nmcp" }
nmcp-aggregation = { id = "com.gradleup.nmcp.aggregation", version.ref = "nmcp" }
```
**DEVIATIONS for agent-tracer** (pins from `.planning/research/STACK.md`, NOT from kore's older catalog — STACK.md is authoritative and must win):
- `kotlin = "2.4.0"` (kore is on 2.3.0)
- `junit5 = "5.14.4"`, `kotest = "6.1.11"`, `mockk = "1.14.11"`
- Add `opentelemetry-proto = "1.10.0-alpha"` and `protobuf-java = "4.35.1"` as libraries (the extractor module's input-shape dependency — keep OUT of `agent-semconv`).
- Add `kotlin-logging-jvm = "8.0.4"` / `logback-classic = "1.5.34"` if logging is needed.
- Drop all kore LLM/storage/Spring/Kafka libraries — not used here.
- Keep `nmcp`/`nmcp-aggregation` for Maven Central (D-03). Gradle wrapper: STACK.md says 9.5.1 (kore's wrapper is `gradle-9.4.1`; bump the `distributionUrl` and SHA accordingly).

---

### `build.gradle.kts` (root)

**Analog:** `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/build.gradle.kts`

**Plugins + subprojects block** (lines 1-27):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
    id("com.gradleup.nmcp.aggregation")
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")
    group = "io.github.unityinflow"
    version = "0.0.1-SNAPSHOT"   // agent-tracer is pre-v0.0.1; kore shows post-release 0.0.2-SNAPSHOT
    repositories { mavenCentral() }
}
```
**nmcp aggregation block** (lines 42-64) — copy the `nmcpAggregation { centralPortal { ... } }` + `dependencies { nmcpAggregation(project(":...")) }` pattern, with `publishingType = "USER_MANAGED"` (manual release) and `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` env vars.
**DEVIATIONS:** `publicationName = "agent-tracer-${project.version}"`; aggregate ONLY the publishable module(s) — `agent-semconv` is the KORE-facing artifact and is definitely published; decide whether `backend` is published this phase (D-03 says agent-semconv is the contract surface). `group = "io.github.unityinflow"` is fixed by the ecosystem. Until v0.0.1, default to `publishToMavenLocal` (D-03) — aggregation block can exist but is not exercised until release.

---

### `buildSrc/build.gradle.kts` + `buildSrc/src/main/kotlin/*.publishing.gradle.kts`

**Analogs:**
- `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/buildSrc/build.gradle.kts`
- `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/buildSrc/src/main/kotlin/kore.publishing.gradle.kts`

**buildSrc build** (full file, 1-17): `kotlin-dsl` plugin + `gradlePluginPortal()`/`mavenCentral()` repos + `implementation("com.gradleup.nmcp:nmcp:1.4.4")` (literal coordinate — convention plugins can't read catalog plugin aliases).

**Publishing convention plugin** (`kore.publishing.gradle.kts`, full file 43-119) — copy verbatim and rename `kore.publishing` → `agenttracer.publishing`:
```kotlin
plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.gradleup.nmcp")
}

java { withSourcesJar(); withJavadocJar() }   // both required by Sonatype

publishing { publications { create<MavenPublication>("maven") {
    from(components["java"])
    pom { /* name, description, url, MIT license, developer, scm, issueManagement */ }
} } }

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])   // Pitfall 5: sign the PUBLICATION
    } else { logger.lifecycle("[agenttracer.publishing] Signing skipped — env vars not set.") }
}
```
**DEVIATIONS:** Change pom `url`/`scm`/`issueManagement` from `github.com/UnityInFlow/kore` → `github.com/UnityInFlow/agent-tracer`. Keep the MIT license, developer (`Jiří Hermann`), and the "sign the publication, not the task" comment. Signing reads `SIGNING_KEY`/`SIGNING_PASSWORD` env vars (never commit secrets — CLAUDE.md).

---

### `agent-semconv/build.gradle.kts` (published, near-zero-dep module)

**Analog:** `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/build.gradle.kts` (lines 1-6, 36-56)

**Module shape:**
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("agenttracer.publishing")        // was kore.publishing
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
publishing { publications { named<MavenPublication>("maven") { pom {
    name.set("agent-tracer — Agent Semconv")
    description.set("Vendored gen_ai.* + unityinflow.* attribute key constants and operation enum.")
} } } }
```
**CRITICAL DEVIATION (D-03 / RESEARCH §Standard Stack):** `agent-semconv` must have **zero or near-zero transitive deps** (ideally only Kotlin stdlib). It is the artifact KORE depends on as an emitter — it must NOT pull opentelemetry-proto, ClickHouse, or any trace backend. Test deps only: `libs.junit5`, `libs.kotest.assertions`, `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`. (kore-core uses `compileOnly` for serialization to keep its runtime classpath clean — same discipline applies here, but agent-semconv likely needs no serialization at all.)

---

### `backend/build.gradle.kts` (extractor + domain module)

**Analog:** `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/build.gradle.kts`

**Test block + toolchain** (lines 23-42) copied directly:
```kotlin
dependencies {
    implementation(project(":agent-semconv"))
    // OTLP proto types are the extractor's INPUT shape (KeyValue/AnyValue).
    implementation(libs.opentelemetry.proto)   // lives HERE, not in agent-semconv

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
```
**Note:** Phase 1 is pure/synchronous — no coroutines, no Ktor, no gRPC, no ClickHouse here (those are Phases 2-5). The `kore-core` pattern of `compileOnly` for optional deps is the model for keeping the classpath minimal. Whether `backend` applies `agenttracer.publishing` depends on the D-03 decision about whether the extractor ships to Maven Central in v0.0.1 (agent-semconv definitely does; the extractor may stay internal).

---

### `agent-semconv/.../GenAiAttributes.kt` + `UnityInFlowAttributes.kt` (key constants)

**Analog:** none — greenfield, establish convention. **Design is fully specified in RESEARCH §Code Examples (lines 347-372)** — copy that as the starting point.

**Idiom (from RESEARCH, grounded in CLAUDE.md "isolate every key string in one place"):** a Kotlin `object` of `const val` key strings, one object per namespace. This is the ONLY place raw `"gen_ai.*"` / `"unityinflow.*"` literals may appear (Pitfall 2 — gen_ai semconv is Development-stability and renames; a rename must be a one-file change).
```kotlin
object GenAiAttributes {
    const val USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens"   // int
    const val OPERATION_NAME = "gen_ai.operation.name"
    // ... full set in RESEARCH §gen_ai.* Attribute Reference table
}
object UnityInFlowAttributes {
    const val CONTEXT_SATURATION_PCT = "unityinflow.context.saturation_pct"  // double 0-100
    const val COST_USD = "unityinflow.cost.usd"                              // double
    const val SKILL_NAME = "unityinflow.skill.name"
    // ... full set in RESEARCH §Convention Document Contents D-04/05/07
}
```

---

### `agent-semconv/.../GenAiOperation.kt` (operation enum)

**Analog (idiom only):** `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentResult.kt` — for the exhaustive-modelling + factory idiom.

**Pattern:** a Kotlin `enum class` for `gen_ai.operation.name` values, with a companion `fromOtel(String): AgentOperation?` factory (nullable — unknown operations map to null, not a throw; D-12 absence-is-information, CLAUDE.md "no exceptions for expected cases"). The four v1.0-relevant values per RESEARCH §gen_ai.* Attribute Reference: `invoke_agent`, `execute_tool`, `chat`/`generate_content`/`text_completion`, `plan`. Use a companion-object factory (CLAUDE.md code style: "companion objects for factory methods").

---

### `backend/.../domain/AgentSpan.kt`, `AgentOutcome.kt`, `AgentOperation.kt` (typed model)

**Analogs:**
- Data class idiom: `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/src/main/kotlin/io/github/unityinflow/kore/core/TokenUsage.kt` (lines 1-18)
- Sealed-class idiom: `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentResult.kt` (lines 26-66)

**`TokenUsage.kt` shows the exact data-class house style** — immutable `val` fields, package `io.github.unityinflow.kore.core`, KDoc one-liner, derived `get()` property:
```kotlin
/** Tracks token consumption for a single LLM call or accumulated over an agent run. */
@Serializable
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}
```
**`AgentResult.kt` shows the sealed-class house style** — `sealed class` with `data class` variants, one KDoc per variant, exhaustive `when`:
```kotlin
sealed class AgentResult {
    data class Success(val output: String, val tokenUsage: TokenUsage) : AgentResult()
    data class Cancelled(val reason: String) : AgentResult()
    // ...
}
```
**Apply to agent-tracer (RESEARCH §System Architecture Diagram, lines 250-258):**
- `AgentSpan` = immutable `data class` of **nullable typed fields** (D-12): `agentName: String?`, `model: String?`, `skillName: String?`, `inputTokens: Long?`, `outputTokens: Long?`, `costUsd: Double?`, `saturationPct: Double?`, `operation: AgentOperation?`, `outcome: AgentOutcome`, `inputPayload: String?`, `outputPayload: String?`, `payloadTruncated: Boolean?`. Token types are `Long?` to preserve OTLP `int_value` (int64) — NOT `String` (Pitfall 1).
- `AgentOutcome` = sealed class or enum for `success|failure|partial|cancelled` (D-09).
- Package: `io.github.unityinflow.agenttracer.domain` (mirror kore's `io.github.unityinflow.kore.core`).
- **Deviation from TokenUsage:** agent-tracer's `@Serializable` is optional here — Phase 1 has no JSON wire; only add kotlinx-serialization if a test fixture needs it. Keep `agent-semconv` serialization-free.

---

### `backend/.../convention/AgentSpanExtractor.kt` + `AttrView` coercion helpers (pure extractor)

**Analog:** none — greenfield, establish convention. **Design fully specified in RESEARCH §Code Examples (lines 375-393)** and §OTLP Value-Type Mapping (lines 200-207).

**Pattern (pure function, no I/O — RESEARCH §Architecture Patterns):**
- Priority-ordered lookup: `unityinflow.*` key first, then `gen_ai.*`, then resource fallback (`service.name`), else `null` (D-11 precedence — MUST be test-verified, roadmap success criterion).
- Type-preserving coercion via an `AttrView` wrapper over the OTLP `KeyValue` list: `.string(key)`, `.long(key)`, `.double(key)`, `.bool(key)`, `.stringList(key)` — each reading the correct `AnyValue` variant (RESEARCH §OTLP Value-Type Mapping table). NEVER stringify a typed value (Pitfall 1).
- `saturationPct?.coerceIn(0.0, 100.0)` clamp (D-04).
- Payloads (`gen_ai.input.messages` / `output.messages`) carried verbatim as `String?`, never JSON-parsed this phase (D-14, Anti-Pattern).
- Extractor must **never throw** on malformed/missing/mistyped input — nullable/sealed handling, not exceptions (Security V5, CLAUDE.md `Result`/sealed over exceptions).

---

### `backend/src/test/.../*Test.kt` (extractor tests)

**Analog:** `/Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentResultTest.kt` (full file 1-46)

**Exact house test style to copy:**
```kotlin
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AgentResultTest {
    @Test
    fun `Success carries output and token usage`() {
        val result = AgentResult.Success(output = "done", tokenUsage = usage)
        result.output shouldBe "done"
    }
}
```
- JUnit 5 `@Test` as runner + Kotest `shouldBe` infix matchers (NOT JUnit assertions, NOT a Kotest spec runner — assertions-only, matching CLAUDE.md/STACK.md).
- Backtick-quoted descriptive method names.
- Named constructor arguments.
- One `@Test` per behaviour; include an exhaustive-`when` test for sealed types (lines 24-35).

**Apply to agent-tracer (RESEARCH §Validation Architecture → Test Map):** dedicated tests named for `*Precedence*` (D-11), `*Clamp*` (D-04), `*Nullable*` (D-12), `*GenAiOnly*` (CONV-02), `*Operation*`, `*ValueType*` (CONV-03), `*Payload*`. Build a golden gen_ai-only span fixture and a full unityinflow.* span fixture, reused across tests (RESEARCH Wave 0 Gaps).

---

### `docs/agent-span-convention.md` (published contract)

**Analog:** none — greenfield. **Full required contents specified in RESEARCH §Convention Document Contents (lines 169-192)** and §OTLP Value-Type Mapping / §Payload Handling Rules. Must include: versioning (semver 1.0, `unityinflow.schema_version`), the single attribute table with an **OTLP value-type column** (CONV-03), the three `unityinflow.*` extensions + reserved set, the "tokens only from `gen_ai.usage.*`" statement (D-06), run-identity/outcome semantics (D-08/09/10), payload handling (256 KB cap + truncation keys, D-13/14), precedence statement (D-11), and the OTel sensitive-data/PII warning (Security V7/V9). Linked from README.

## Shared Patterns

### Kotlin house style (establish fresh, mirror kore-runtime exactly)
**Source:** `08-kore-runtime/kore-core/src/main/kotlin/.../TokenUsage.kt`, `AgentResult.kt` + CLAUDE.md Kotlin rules
**Apply to:** every `.kt` file this phase creates
- Package root `io.github.unityinflow.agenttracer.*` (and `io.github.unityinflow.agentsemconv` for the constants module) — mirrors kore's `io.github.unityinflow.kore.*`.
- `val` only, never `var`. No `!!` without a safety comment. Immutable `data class` for value objects; `sealed class` for outcomes/states; `enum class` + companion `fromX` factory for closed string sets.
- Nullable typed fields for absent data (D-12) — no sentinel defaults.
- `Result<T>`/sealed/nullable over exceptions for expected failures (extractor never throws).
- One-line KDoc on every public type.
- ktlint (via `org.jmailen.kotlinter`, applied to all subprojects in root `build.gradle.kts` line 19) before every commit.

### Build & publishing (copy from kore-runtime, retarget to agent-tracer)
**Source:** `08-kore-runtime/{settings,build}.gradle.kts`, `buildSrc/...kore.publishing.gradle.kts`, `gradle/libs.versions.toml`
**Apply to:** all build files
- Gradle Kotlin DSL only, version catalog (`gradle/libs.versions.toml`), `jvmToolchain(21)`, `useJUnitPlatform()`.
- `group = "io.github.unityinflow"`; convention plugin in `buildSrc` for Maven Central publishing; sign the PUBLICATION not the task; secrets via `SIGNING_KEY`/`SIGNING_PASSWORD`/`SONATYPE_*` env vars only.
- Pre-v0.0.1: `publishToMavenLocal` (D-03); version `0.0.1-SNAPSHOT`.

### Test convention (copy from kore-runtime)
**Source:** `08-kore-runtime/kore-core/src/test/kotlin/.../AgentResultTest.kt`
**Apply to:** all test files — JUnit 5 `@Test` runner + Kotest `shouldBe` assertions-only, backtick method names, named args, >80% coverage on the extractor/model.

## No Analog Found

Files with no in-codebase analog (planner uses RESEARCH.md sections, which contain explicit design sketches):

| File | Role | Reason | Use Instead |
|------|------|--------|-------------|
| `docs/agent-span-convention.md` | documentation | First convention doc in the ecosystem; bespoke contract | RESEARCH §Convention Document Contents (full spec) |
| `agent-semconv/.../GenAiAttributes.kt` + `UnityInFlowAttributes.kt` | constants | No prior key-registry object exists | RESEARCH §Code Examples lines 347-372 |
| `backend/.../convention/AgentSpanExtractor.kt` + `AttrView` | pure extractor | No prior OTLP→typed extractor exists | RESEARCH §Code Examples lines 375-393 + §OTLP Value-Type Mapping |

(Note: `GenAiOperation.kt` and the domain `AgentSpan`/`AgentOutcome` classes have an *idiom* analog in kore — the sealed/data/enum modelling style — but no behavioural analog; their content comes from RESEARCH.)

## Metadata

**Analog search scope:** `09-agent-tracer/` (confirmed greenfield — no `.kt`/`.gradle.kts`), then sibling ecosystem repos under `unity-in-flow-ai/` — primary analog `08-kore-runtime` (multi-module Kotlin + version catalog + Maven Central, same group/phase); secondary `05-budget-breaker`, `06-token-dashboard`.
**Files scanned:** kore-runtime `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `buildSrc/build.gradle.kts`, `buildSrc/src/main/kotlin/kore.publishing.gradle.kts`, `kore-core/build.gradle.kts`, `kore-core/.../AgentResult.kt`, `kore-core/.../TokenUsage.kt`, `kore-core/src/test/.../AgentResultTest.kt`.
**Pattern extraction date:** 2026-06-13
