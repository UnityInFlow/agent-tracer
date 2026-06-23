---
phase: 04-query-api-demo-emitter-compose-stack
plan: 03
subsystem: demo-emitter
tags: [demo-emitter, otlp-client, otel-sdk, seeded-generation, saturation, convention, wave-2]
requires:
  - "agent-semconv (GenAiAttributes/UnityInFlowAttributes/GenAiOperation constants — the only attribute-KEY source)"
  - "gradle/libs.versions.toml otel-bom/otel-sdk/otel-exporter-otlp + coroutines/junit5/kotest/logback aliases"
  - "Phase-3 OTLP/HTTP receiver on :4318 (the runtime export target; not built here)"
provides:
  - "demo-emitter Gradle module (kotlin-jvm + application; installDist runnable distribution)"
  - "SeededRunFactory(Random(seed)) — deterministic full-convention nested-subagent span-tree generator (sole producer of context_saturation_pct data)"
  - "RunScenario sealed model (invoke_agent root -> plan -> skills + nested subagents + tools)"
  - "DemoEmitter.main — one-shot OTLP/HTTP export of N seeded runs then bounded-flush exit"
  - "DemoConfig.fromEnv (OTLP_ENDPOINT/DEMO_SEED/DEMO_RUNS named-constant env helper)"
affects:
  - "Plan 04 (query API / demo data) — the emitter is the realistic-data producer the cold-start ingest proof and Phase-5 frontend consume"
  - "Plan 05 (Compose stack) — installDist launcher feeds the seeder image; OTLP_ENDPOINT default targets the in-Compose tracer"
  - "STATE saturation blocker — demo-emitter is the mitigation; context_saturation_pct now has a real producer (SC3 unblocked)"
tech-stack:
  added:
    - "opentelemetry-sdk + opentelemetry-exporter-otlp (impl, via otel-bom) — OTLP/HTTP client role"
    - "opentelemetry-sdk-testing 1.63.0 (testImpl) — InMemorySpanExporter for in-process determinism capture"
    - "Gradle application plugin (demo-emitter) — installDist/run for the Compose seeder"
  patterns:
    - "Resolve an immutable seeded scenario first, then emit as a pure walk — determinism isolated from the OTel side effects (SC4)"
    - "All convention attribute KEYS via AttributeKey.*Key(<agent-semconv constant>); zero hardcoded gen_ai.*/unityinflow.* literals (CONV-01)"
    - "fromEnv named-ENV-constant config helper (mirrors ClickHouseConfig.fromEnv)"
    - "Bounded shutdown().join(timeout) as the one-shot flush barrier — no Thread.sleep, no raw threads"
key-files:
  created:
    - "demo-emitter/build.gradle.kts"
    - "demo-emitter/src/main/kotlin/io/github/unityinflow/agenttracer/demo/RunScenario.kt"
    - "demo-emitter/src/main/kotlin/io/github/unityinflow/agenttracer/demo/SeededRunFactory.kt"
    - "demo-emitter/src/main/kotlin/io/github/unityinflow/agenttracer/demo/DemoEmitter.kt"
    - "demo-emitter/src/test/kotlin/io/github/unityinflow/agenttracer/demo/SeededRunFactoryTest.kt"
  modified:
    - "settings.gradle.kts"
    - "gradle/libs.versions.toml"
decisions:
  - "RisingSaturation uses a single sanctioned, comment-justified private `var` accumulator (isolated to a tiny private class, never escapes) — the monotonic series is inherently stateful; same documented var-exception class as Phase-2 SpanRow"
  - "Skill activations modelled as `chat` operation spans carrying unityinflow.skill.name; subagents as nested `invoke_agent` via Context.with(parentSpan).setParent — matches GenAiOperation.fromOtel parse values"
  - "Saturation sampled on agents/plan every node + ~half of skills/tools so the series is sparse-but-rising; emitter is the SOLE saturation producer (Pitfall 6 mitigation)"
  - "DemoConfig tolerates unparseable DEMO_SEED/DEMO_RUNS by falling back to defaults (dev tool — a typo must not crash the one-shot run); runs coerced >= 0 (T-04-09)"
  - "Added opentelemetry-sdk-testing (test scope) for InMemorySpanExporter — first-party OTel artifact, no slopcheck gate; lets SC4 determinism be proven with NO live receiver"
metrics:
  duration: ~10 min
  tasks: 3
  files: 7
  completed: 2026-06-23
---

# Phase 4 Plan 03: Demo Emitter (seeded full-convention OTLP producer) Summary

Built the `demo-emitter` module (TOOL-01/SC4/D-06): a runnable OTLP/HTTP *client* of the Phase-3 receiver whose `main` emits N seeded, realistic, full-convention agent runs — `invoke_agent` root → `plan` → skills + ≥2 nested-subagent levels + tools, every node carrying cost/tokens and a rising `unityinflow.context.saturation_pct` series — then bounded-flushes and exits. Same seed reproduces an identical span tree. It is the sole producer of context-saturation data, mitigating the long-standing STATE saturation blocker for SC3.

## What Was Built

### Task 1 — Scaffold the demo-emitter Gradle module (`c400ecc`)
- `settings.gradle.kts`: added `"demo-emitter"` to `include(...)`.
- `demo-emitter/build.gradle.kts`: `alias(libs.plugins.kotlin.jvm)` + `application` (no serialization plugin — no `@Serializable` types); `jvmToolchain(21)`; `tasks.test { useJUnitPlatform() }`; `mainClass = "io.github.unityinflow.agenttracer.demo.DemoEmitterKt"`. Deps via catalog only: `platform(otel.bom)` + `otel.sdk` + `otel.exporter.otlp` + `project(":agent-semconv")` + `coroutines.core` (impl), `logback.classic` (runtimeOnly); `junit5`/`kotest.assertions`/`junit-platform-launcher` (test). Inherits group/version/kotlinter from the root `subprojects {}`.
- Verified: `installDist` task present on `:demo-emitter`; empty module compiles (`:demo-emitter:compileKotlin` NO-SOURCE BUILD SUCCESSFUL).

### Task 2 — SeededRunFactory + RunScenario, full-convention deterministic trees (`ae7226f`) [TDD]
- `RunScenario.kt`: a `data class RunScenario` + sealed `interface ScenarioNode` with `Agent`/`Plan`/`Skill`/`Tool` variants, each carrying its resolved tokens/cost/saturation and node-specific fields. Lowercase OTLP operation values (`invoke_agent`/`plan`/`chat`/`execute_tool`) as constants.
- `SeededRunFactory.kt`: `class SeededRunFactory(rng: Random)`. `nextScenario()` resolves a complete immutable tree from `rng` (skill count 3–5, ≥2 guaranteed nested-subagent levels, tokens/cost/outcome, a `RisingSaturation` accumulator); `generateRun(tracer)` then walks it building nested spans via `spanBuilder(op).setParent(parent).setAttribute(...).startSpan()`, recursing under `parent.with(span)` and ending in post-order. **Every attribute KEY comes from `AttributeKey.*Key(GenAiAttributes.X / UnityInFlowAttributes.Y)`** — no hardcoded literals.
- `SeededRunFactoryTest.kt` (4 block-body `@Test`, none `@Disabled`): `sameSeedProducesIdenticalSpanTrees` (normalized name+attribute-map projection of two same-seed 4-run captures compares equal), `differentSeedsDiverge`, `everyRunExercisesTheFullConvention` (≥1 saturation, ≥3 skills, cost on every span, ≥1 tool, ≥2 `invoke_agent` levels, ≥1 `plan`, rising saturation range), `outcomesVaryAcrossRuns` (≥2 distinct outcomes over 12 runs). Uses `InMemorySpanExporter` — no live receiver.
- Verified GREEN: `:demo-emitter:test --tests '*SeededRunFactory*'` BUILD SUCCESSFUL (this is a pure-JVM test — it actually runs, per the environment note); key gate `grep '"gen_ai\.\|"unityinflow\.' demo-emitter/src/main` CLEAN; ktlint clean.

### Task 3 — DemoEmitter.main, OTLP export + one-shot exit (`225518e`)
- `DemoEmitter.kt`: `data class DemoConfig` with `fromEnv` reading `OTLP_ENDPOINT` (default `http://localhost:4318/v1/traces`), `DEMO_SEED` (42), `DEMO_RUNS` (20) via named ENV constants — the `ClickHouseConfig.fromEnv` idiom. `fun main()` builds `OtlpHttpSpanExporter.builder().setEndpoint(...).build()`, wraps it in `SdkTracerProvider(BatchSpanProcessor)` with a `service.name=demo-agent` resource, runs `repeat(runs) { factory.generateRun(tracer) }`, then a bounded `tracerProvider.shutdown().join(30s)` flush-barrier in `finally` and exits. No `var`, no `!!`, no `Thread.sleep`.
- Verified: `:demo-emitter:installDist` produces `demo-emitter/build/install/demo-emitter/bin/demo-emitter`; `grep 'Thread.sleep\|!!' demo-emitter/src/main` CLEAN.

## Verification Evidence

- `./gradlew :demo-emitter:build` — BUILD SUCCESSFUL (compile + 4-test determinism/convention suite + ktlint).
- `./gradlew :demo-emitter:installDist` — runnable launcher present at `build/install/demo-emitter/bin/demo-emitter`.
- `grep -rn '"gen_ai\.\|"unityinflow\.' demo-emitter/src/main` — empty (keys only from agent-semconv constants, CONV-01).
- `grep -rn 'Thread.sleep' demo-emitter/src/main` — empty.
- `settings.gradle.kts` includes `demo-emitter`; `installDist` task present on the module.

## Deviations from Plan

**1. [Rule 3 - Blocking] Added `opentelemetry-sdk-testing` test dependency + catalog alias**
- **Found during:** Task 2.
- **Issue:** The plan's determinism assertion (compare two same-seed captures in-process) needs `InMemorySpanExporter`, which ships in `opentelemetry-sdk-testing`, not the `opentelemetry-sdk` the plan listed. Without it the test cannot capture spans without a live receiver.
- **Fix:** Added `otel-sdk-testing = { module = "io.opentelemetry:opentelemetry-sdk-testing" }` to the catalog (version-managed by `otel-bom`) and `testImplementation(libs.otel.sdk.testing)` to the module. First-party CNCF/OTel artifact — same trust class as the already-approved OTel deps (threat T-04-SC), no checkpoint required.
- **Files modified:** `gradle/libs.versions.toml`, `demo-emitter/build.gradle.kts`.
- **Commit:** `ae7226f`.

**2. [Rule 3 - Blocking] Kotest matcher name correction**
- **Issue:** Drafted the convention test with `shouldBeGreaterThanOrEqual`; Kotest 6 exports the infix as `shouldBeGreaterThanOrEqualTo` (`io.kotest.matchers.comparables`).
- **Fix:** Renamed all usages + the import. No behavior change.
- **Commit:** `ae7226f`.

Note: a sanctioned, comment-justified private `var` (`RisingSaturation.current`) is used for the monotonic saturation accumulator — isolated to a tiny private class, never escaping. This follows the documented Phase-2 var-exception precedent (SpanRow) and is recorded in `decisions` above, not a deviation.

## TDD Gate Compliance

Task 2 was authored test-first conceptually but committed as a single `feat` (factory + test together) since the test and the production seam are co-designed (the determinism contract IS the factory's API). The test genuinely **runs and passes** (pure-JVM, no Testcontainers) — verified GREEN before commit. No `test(...)`-then-`feat(...)` split commit was produced; if strict RED/GREEN gate commits are required, this is the only divergence and the test is honestly passing (not `@Disabled`, not faked).

## Integration-Test / Sandbox Note

No Testcontainers / container-launching tests were added or run by this plan. `SeededRunFactoryTest` is pure-JVM (deterministic seeded generation + `InMemorySpanExporter`) and runs green locally, exactly as the environment note anticipated. The emitter's live OTLP export to a real `:4318` receiver is exercised end-to-end by the Plan 05 Compose cold-start proof, not here. No change to the carried-forward Phase-2/3 Ryuk/Colima IT debt.

## Self-Check: PASSED

- Created files: `demo-emitter/build.gradle.kts`, `RunScenario.kt`, `SeededRunFactory.kt`, `DemoEmitter.kt`, `SeededRunFactoryTest.kt` — all FOUND.
- Modified files: `settings.gradle.kts`, `gradle/libs.versions.toml` — all FOUND.
- Commits `c400ecc`, `ae7226f`, `225518e` — all FOUND in git log.
