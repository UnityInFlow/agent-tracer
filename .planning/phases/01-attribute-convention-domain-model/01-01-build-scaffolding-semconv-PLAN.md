---
phase: 01-attribute-convention-domain-model
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - settings.gradle.kts
  - backend/build.gradle.kts
  - build.gradle.kts
  - gradlew
  - gradlew.bat
  - gradle/libs.versions.toml
  - gradle/wrapper/gradle-wrapper.properties
  - buildSrc/build.gradle.kts
  - buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts
  - agent-semconv/build.gradle.kts
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/UnityInFlowAttributes.kt
  - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt
  - agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/GenAiOperationTest.kt
  - agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/AttributeKeysTest.kt
autonomous: true
requirements: [CONV-01]
must_haves:
  truths:
    - "`./gradlew test` runs green on a freshly scaffolded multi-module build"
    - "Every gen_ai.* and unityinflow.* attribute key string lives in exactly one place (the agent-semconv constants objects)"
    - "gen_ai.operation.name string values parse into a typed GenAiOperation enum; unknown values return null without throwing"
  artifacts:
    - path: "settings.gradle.kts"
      provides: "Multi-module include of agent-semconv and backend"
      contains: "agent-semconv"
    - path: "gradle/libs.versions.toml"
      provides: "Version catalog pinning Kotlin 2.4.0, JUnit 5.14.4, Kotest 6.1.11, opentelemetry-proto 1.10.0-alpha"
      contains: "kotest"
    - path: "agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt"
      provides: "Vendored gen_ai.* key constants — the only place these literals appear"
      contains: "gen_ai.usage.input_tokens"
    - path: "agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/UnityInFlowAttributes.kt"
      provides: "Vendored unityinflow.* key constants"
      contains: "unityinflow.cost.usd"
    - path: "agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt"
      provides: "Typed operation enum with fromOtel factory"
      contains: "fromOtel"
  key_links:
    - from: "agent-semconv/build.gradle.kts"
      to: "buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts"
      via: "id(\"agenttracer.publishing\")"
      pattern: "agenttracer\\.publishing"
---

<objective>
Establish the agent-tracer Gradle multi-module build (Wave 0 infrastructure) and the published `agent-semconv` module that vendors every attribute key string as Kotlin constants plus a typed `GenAiOperation` enum. This is the contract's machine-readable surface (CONV-01): KORE and other Kotlin emitters depend on this artifact instead of hardcoding `gen_ai.*` / `unityinflow.*` strings.

Purpose: Without a build, nothing compiles or tests; without the constants module, every downstream file would scatter Development-stability key literals (Pitfall 2). Isolating keys in one place makes a future gen_ai rename a one-file change.
Output: A green `./gradlew test`, a two-module project (`agent-semconv`, `backend`), version catalog, publishing convention plugin, and the constants + operation enum with tests.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/01-attribute-convention-domain-model/01-CONTEXT.md
@.planning/phases/01-attribute-convention-domain-model/01-RESEARCH.md
@.planning/phases/01-attribute-convention-domain-model/01-PATTERNS.md
</context>

<tasks>

<task type="auto">
  <name>Task 1: Scaffold the Gradle multi-module build (Wave 0 infra)</name>
  <files>settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle/wrapper/gradle-wrapper.properties, buildSrc/build.gradle.kts, buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts, agent-semconv/build.gradle.kts, backend/build.gradle.kts</files>
  <read_first>
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/settings.gradle.kts (multi-module include idiom)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/build.gradle.kts (root plugins + subprojects + nmcp aggregation block)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/gradle/libs.versions.toml (version catalog structure: [versions]/[libraries]/[plugins])
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/gradle/wrapper/gradle-wrapper.properties (wrapper distributionUrl form)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/buildSrc/build.gradle.kts (kotlin-dsl plugin + literal nmcp coordinate)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/buildSrc/src/main/kotlin/kore.publishing.gradle.kts (publishing convention plugin to rename kore→agenttracer)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/build.gradle.kts (per-module build: jvmToolchain(21), useJUnitPlatform, test deps)
    - .planning/phases/01-attribute-convention-domain-model/01-PATTERNS.md (build & publishing section, with exact DEVIATIONS)
    - .planning/phases/01-attribute-convention-domain-model/01-RESEARCH.md (Recommended Project Structure; Standard Stack version table)
  </read_first>
  <action>
    Copy the kore-runtime build scaffolding structurally and retarget to agent-tracer, applying PATTERNS.md DEVIATIONS exactly.

    settings.gradle.kts: `rootProject.name = "agent-tracer"`; `include("agent-semconv", "backend")`. No frontend modules.

    gradle/libs.versions.toml: `[versions]` pinning `kotlin = "2.4.0"`, `junit5 = "5.14.4"`, `kotest = "6.1.11"`, `mockk = "1.14.11"`, `opentelemetry-proto = "1.10.0-alpha"`, `protobuf-java = "4.35.1"`, `kotlinter` and `nmcp` versions as in kore. `[libraries]` entries: `junit5`, `kotest-assertions` (kotest-assertions-core), `mockk`, `opentelemetry-proto` (group `io.opentelemetry.proto`, artifact `opentelemetry-proto`), `protobuf-java`. `[plugins]`: `kotlin-jvm`, `kotlinter`, `nmcp`, `nmcp-aggregation`. Do NOT include kotlin-serialization unless a test fixture later needs it. Drop ALL kore LLM/storage/Spring/Kafka libraries.

    gradle/wrapper/gradle-wrapper.properties: set `distributionUrl` to Gradle 9.5.1 (bump from kore's 9.4.1). Also copy `gradlew`/`gradlew.bat` from kore so `./gradlew` works.

    build.gradle.kts (root): `plugins { alias(libs.plugins.kotlin.jvm) apply false; alias(libs.plugins.kotlinter) apply false; id("com.gradleup.nmcp.aggregation") }`. `subprojects { apply(plugin = "org.jmailen.kotlinter"); group = "io.github.unityinflow"; version = "0.0.1-SNAPSHOT"; repositories { mavenCentral() } }`. Copy the nmcpAggregation block with `publishingType = "USER_MANAGED"` and `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` env vars; aggregate `project(":agent-semconv")` only (the KORE-facing artifact); `publicationName = "agent-tracer-${project.version}"`.

    buildSrc/build.gradle.kts: `kotlin-dsl` plugin, `gradlePluginPortal()` + `mavenCentral()` repos, `implementation("com.gradleup.nmcp:nmcp:1.4.4")` literal coordinate.

    buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts: copy kore.publishing.gradle.kts verbatim, rename plugin id `kore.publishing` → `agenttracer.publishing`. Retarget pom `url`/`scm`/`issueManagement` to `github.com/UnityInFlow/agent-tracer`. Keep MIT license, developer `Jiří Hermann`, and `sign(publishing.publications["maven"])` (sign the publication, not the task). Signing reads `SIGNING_KEY`/`SIGNING_PASSWORD` env vars; skip gracefully when absent.

    agent-semconv/build.gradle.kts: `plugins { alias(libs.plugins.kotlin.jvm); id("agenttracer.publishing") }`; `kotlin { jvmToolchain(21) }`; `tasks.test { useJUnitPlatform() }`. CRITICAL: zero/near-zero transitive deps (only Kotlin stdlib at runtime) — do NOT depend on opentelemetry-proto here. Test deps only: `testImplementation(libs.junit5)`, `testImplementation(libs.kotest.assertions)`, `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`. Set pom name "agent-tracer — Agent Semconv" and description about vendored attribute key constants + operation enum.

    backend/build.gradle.kts: `plugins { alias(libs.plugins.kotlin.jvm) }` (NOT publishing — extractor stays internal in v0.0.1 per D-03). Dependencies: `implementation(project(":agent-semconv"))`, `implementation(libs.opentelemetry.proto)` (OTLP KeyValue/AnyValue input shape lives HERE), test deps `libs.junit5` + `libs.kotest.assertions` + junit-platform-launcher. `kotlin { jvmToolchain(21) }`; `tasks.test { useJUnitPlatform() }`. No coroutines/Ktor/gRPC/ClickHouse this phase. Create the empty backend source dirs so the module resolves.
  </action>
  <verify>
    <automated>cd /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/09-agent-tracer && ./gradlew test</automated>
  </verify>
  <acceptance_criteria>
    - `./gradlew test` exits 0 on the freshly scaffolded build (modules may have no/empty tests yet — green is the bar).
    - `settings.gradle.kts` contains `rootProject.name = "agent-tracer"` and `include("agent-semconv", "backend")`.
    - `gradle/libs.versions.toml` contains `kotlin = "2.4.0"`, `kotest = "6.1.11"`, `junit5 = "5.14.4"`, and an `opentelemetry-proto` library entry.
    - `grep -rn 'opentelemetry-proto' agent-semconv/build.gradle.kts` returns no matches (agent-semconv has no OTLP dependency).
    - `backend/build.gradle.kts` contains `implementation(project(":agent-semconv"))` and `implementation(libs.opentelemetry.proto)`.
    - `gradle/wrapper/gradle-wrapper.properties` references gradle-9.5.1.
    - `grep -rn 'kore.publishing' buildSrc/` returns no matches (renamed to agenttracer.publishing).
  </acceptance_criteria>
  <done>Multi-module build scaffolded; `./gradlew test` green; version catalog, publishing convention plugin, and both module build files in place with agent-tracer targeting and STACK.md pins.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Vendor attribute key constants + GenAiOperation enum</name>
  <files>agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt, agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/UnityInFlowAttributes.kt, agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt, agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/GenAiOperationTest.kt, agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/AttributeKeysTest.kt</files>
  <read_first>
    - .planning/phases/01-attribute-convention-domain-model/01-RESEARCH.md (Code Examples: vendored keys object; gen_ai.* Attribute Reference table; operation enum values)
    - .planning/phases/01-attribute-convention-domain-model/01-PATTERNS.md (GenAiAttributes/UnityInFlowAttributes idiom; GenAiOperation enum + companion fromOtel factory)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/src/main/kotlin/io/github/unityinflow/kore/core/AgentResult.kt (sealed/enum + companion factory house style)
    - /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/08-kore-runtime/kore-core/src/test/kotlin/io/github/unityinflow/kore/core/AgentResultTest.kt (JUnit5 @Test + Kotest shouldBe test style)
  </read_first>
  <behavior>
    - Test: `GenAiOperation.fromOtel("invoke_agent")` returns the INVOKE_AGENT variant.
    - Test: `GenAiOperation.fromOtel("execute_tool")` returns the EXECUTE_TOOL variant.
    - Test: `GenAiOperation.fromOtel("chat")` returns the CHAT variant (and generate_content/text_completion map to their LLM-call variants).
    - Test: `GenAiOperation.fromOtel("plan")` returns the PLAN variant.
    - Test: `GenAiOperation.fromOtel("not_a_real_operation")` returns null (no throw — D-12 absence-is-information).
    - Test: key constants resolve to exact strings, e.g. `GenAiAttributes.USAGE_INPUT_TOKENS shouldBe "gen_ai.usage.input_tokens"` and `UnityInFlowAttributes.COST_USD shouldBe "unityinflow.cost.usd"`.
  </behavior>
  <action>
    Create three Kotlin files in package `io.github.unityinflow.agentsemconv`. Use `object` of `const val` per RESEARCH Code Examples — this is the ONLY place raw `gen_ai.*`/`unityinflow.*` literals may appear (Pitfall 2).

    GenAiAttributes.kt — object with: OPERATION_NAME `gen_ai.operation.name`, PROVIDER_NAME `gen_ai.provider.name`, REQUEST_MODEL `gen_ai.request.model`, RESPONSE_MODEL `gen_ai.response.model`, USAGE_INPUT_TOKENS `gen_ai.usage.input_tokens`, USAGE_OUTPUT_TOKENS `gen_ai.usage.output_tokens`, AGENT_NAME `gen_ai.agent.name`, AGENT_ID `gen_ai.agent.id`, TOOL_NAME `gen_ai.tool.name`, TOOL_CALL_ID `gen_ai.tool.call.id`, CONVERSATION_ID `gen_ai.conversation.id`, RESPONSE_FINISH_REASONS `gen_ai.response.finish_reasons`, INPUT_MESSAGES `gen_ai.input.messages`, OUTPUT_MESSAGES `gen_ai.output.messages`, SYSTEM_INSTRUCTIONS `gen_ai.system_instructions`. Also the OTel resource key `service.name` as `SERVICE_NAME` (used for the agent-identity fallback D-10).

    UnityInFlowAttributes.kt — object with: SKILL_NAME `unityinflow.skill.name`, SKILL_VERSION `unityinflow.skill.version`, CONTEXT_SATURATION_PCT `unityinflow.context.saturation_pct`, COST_USD `unityinflow.cost.usd`, PLAN_STEP `unityinflow.plan.step`, OUTCOME `unityinflow.outcome`, SCHEMA_VERSION `unityinflow.schema_version`, PAYLOAD_TRUNCATED `unityinflow.payload.truncated`, PAYLOAD_ORIGINAL_SIZE_BYTES `unityinflow.payload.original_size_bytes`.

    GenAiOperation.kt — `enum class GenAiOperation` with the v1.0-relevant variants: INVOKE_AGENT, EXECUTE_TOOL, CHAT, GENERATE_CONTENT, TEXT_COMPLETION, PLAN, CREATE_AGENT (and any other operation enum values from RESEARCH you want represented). Add a companion `fromOtel(value: String): GenAiOperation?` mapping each OTel string (`invoke_agent`, `execute_tool`, `chat`, `generate_content`, `text_completion`, `plan`, `create_agent`, ...) to its variant, returning null for unknown values (no throw). One-line KDoc on the enum.

    Write tests FIRST per the <behavior> block: GenAiOperationTest.kt (fromOtel mappings + null on unknown) and AttributeKeysTest.kt (constant string values). Use JUnit 5 `@Test` runner + Kotest `shouldBe`, backtick method names, per the AgentResultTest analog.
  </action>
  <verify>
    <automated>cd /Users/jirihermann/Documents/workspace-1-ideas/unity-in-flow-ai/09-agent-tracer && ./gradlew :agent-semconv:test</automated>
  </verify>
  <acceptance_criteria>
    - `./gradlew :agent-semconv:test` exits 0.
    - `grep -c 'gen_ai\.' agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt` is greater than 10.
    - `GenAiOperationTest` asserts `fromOtel("invoke_agent")` is non-null and `fromOtel("not_a_real_operation")` is null.
    - `grep -rn 'gen_ai\.' agent-semconv/src/main backend/src 2>/dev/null | grep -v 'agentsemconv/GenAiAttributes.kt'` returns no matches (every gen_ai literal lives only in GenAiAttributes.kt — verified again in Plan 02).
    - No `var`, no `!!` in the created files.
  </acceptance_criteria>
  <done>agent-semconv ships GenAiAttributes, UnityInFlowAttributes, and GenAiOperation with a null-returning fromOtel factory; tests green; all attribute key literals isolated to the two constants objects.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| build env → Maven Central | publishing convention plugin reads SIGNING_KEY/SONATYPE_* from env (no secrets in repo) |
| (no runtime boundary this plan) | agent-semconv is pure constants + enum; no network, no I/O, no untrusted input parsing yet |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-01 | Information Disclosure | publishing convention plugin / signing keys | mitigate | Credentials read only from `SIGNING_KEY`/`SIGNING_PASSWORD`/`SONATYPE_*` env vars; plugin skips signing gracefully when absent; no secrets committed (CLAUDE.md) |
| T-01-02 | Tampering | dependency resolution (Gradle) | accept | Versions pinned via STACK.md-verified catalog; no new external runtime packages introduced this plan (LOW: build-time only, pinned coordinates) |
| T-01-SC | Tampering | npm/pip/cargo/maven installs | accept | No new external coordinate added beyond STACK.md-verified pins (opentelemetry-proto, JUnit, Kotest, MockK, nmcp); RESEARCH Package Legitimacy Audit records "no new package" — no checkpoint required |
</threat_model>

<verification>
- `./gradlew test` green (whole multi-module build compiles and tests pass).
- `./gradlew :agent-semconv:test` green.
- `./gradlew ktlintCheck` (or kotlinter `lintKotlin`) clean on created files.
- No raw `gen_ai.`/`unityinflow.` literals outside the two constants objects.
</verification>

<success_criteria>
- Two-module Gradle build (`agent-semconv`, `backend`) scaffolded; `./gradlew test` exits 0.
- Version catalog pins Kotlin 2.4.0, JUnit 5.14.4, Kotest 6.1.11, opentelemetry-proto 1.10.0-alpha.
- agent-semconv has zero OTLP/runtime transitive deps; ships GenAiAttributes, UnityInFlowAttributes, GenAiOperation.
- CONV-01 machine surface (typed attribute key constants) exists and is tested.
</success_criteria>

<artifacts_produced>
## Artifacts this phase produces (Plan 01)

New Gradle modules:
- `agent-semconv` (published module, group `io.github.unityinflow`)
- `backend` (internal module; populated in Plan 02)

New build files:
- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`
- `buildSrc/build.gradle.kts`, `buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts`
- `agent-semconv/build.gradle.kts`, `backend/build.gradle.kts`

New Kotlin symbols (package `io.github.unityinflow.agentsemconv`):
- `object GenAiAttributes` — const vals: OPERATION_NAME, PROVIDER_NAME, REQUEST_MODEL, RESPONSE_MODEL, USAGE_INPUT_TOKENS, USAGE_OUTPUT_TOKENS, AGENT_NAME, AGENT_ID, TOOL_NAME, TOOL_CALL_ID, CONVERSATION_ID, RESPONSE_FINISH_REASONS, INPUT_MESSAGES, OUTPUT_MESSAGES, SYSTEM_INSTRUCTIONS, SERVICE_NAME
- `object UnityInFlowAttributes` — const vals: SKILL_NAME, SKILL_VERSION, CONTEXT_SATURATION_PCT, COST_USD, PLAN_STEP, OUTCOME, SCHEMA_VERSION, PAYLOAD_TRUNCATED, PAYLOAD_ORIGINAL_SIZE_BYTES
- `enum class GenAiOperation` — variants: INVOKE_AGENT, EXECUTE_TOOL, CHAT, GENERATE_CONTENT, TEXT_COMPLETION, PLAN, CREATE_AGENT; companion `fromOtel(String): GenAiOperation?`

New test files:
- `GenAiOperationTest.kt`, `AttributeKeysTest.kt`

Build plugin id: `agenttracer.publishing`
</artifacts_produced>

<output>
Create `.planning/phases/01-attribute-convention-domain-model/01-01-SUMMARY.md` when done
</output>
