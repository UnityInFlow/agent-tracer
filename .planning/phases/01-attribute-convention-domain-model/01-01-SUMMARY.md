---
phase: 01-attribute-convention-domain-model
plan: 01
subsystem: infra
tags: [kotlin, gradle, version-catalog, maven-central, opentelemetry, gen-ai-semconv, otlp]

# Dependency graph
requires: []
provides:
  - Gradle multi-module build (agent-semconv + backend) with version catalog and JVM 21 toolchain
  - agenttracer.publishing convention plugin (signed Maven Central publishing via nmcp)
  - agent-semconv published module — zero-runtime-dep contract artifact for KORE emitters
  - GenAiAttributes + UnityInFlowAttributes constant objects (single source of truth for all attribute key strings)
  - GenAiOperation enum with null-returning fromOtel factory
affects: [02-domain-model-extractor, 03-otlp-ingestion, 04-query-api-compose, 05-frontend, kore-instrumentation]

# Tech tracking
tech-stack:
  added:
    - Kotlin 2.4.0 (JVM 21)
    - Gradle 9.5.1 (Kotlin DSL, version catalog)
    - JUnit 5.14.4 + Kotest 6.1.11 (assertions-only)
    - opentelemetry-proto 1.10.0-alpha + protobuf-java 4.35.1 (backend module only)
    - nmcp 1.4.4 (Sonatype Central Portal aggregation)
    - kotlinter 5.0.0 (ktlint)
  patterns:
    - "Attribute key literals isolated to constants objects (CONV-01): gen_ai.*/unityinflow.* strings live in exactly one place"
    - "Null-returning fromOtel companion factory for closed string sets (absence-is-information, no throw)"
    - "Publishing convention plugin in buildSrc; sign the publication not the task; secrets via env vars only"
    - "agent-semconv kept dependency-free; OTLP proto types confined to the backend module"

key-files:
  created:
    - settings.gradle.kts
    - build.gradle.kts
    - gradle/libs.versions.toml
    - gradle/wrapper/gradle-wrapper.properties
    - buildSrc/build.gradle.kts
    - buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts
    - agent-semconv/build.gradle.kts
    - backend/build.gradle.kts
    - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiAttributes.kt
    - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/UnityInFlowAttributes.kt
    - agent-semconv/src/main/kotlin/io/github/unityinflow/agentsemconv/GenAiOperation.kt
    - agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/GenAiOperationTest.kt
    - agent-semconv/src/test/kotlin/io/github/unityinflow/agentsemconv/AttributeKeysTest.kt
    - .gitignore
  modified: []

key-decisions:
  - "agent-semconv published; backend extractor stays internal in v0.0.1 (D-03) — only agent-semconv aggregated for Maven Central"
  - "STACK.md pins win over kore's older catalog: Kotlin 2.4.0, Gradle 9.5.1, JUnit 5.14.4"
  - "opentelemetry-proto confined to backend module to keep agent-semconv a near-zero-dep KORE-facing artifact"

patterns-established:
  - "CONV-01 key isolation: every gen_ai.*/unityinflow.* literal lives only in GenAiAttributes/UnityInFlowAttributes"
  - "fromOtel(String): GenAiOperation? returns null for unknown values (D-12 absence-is-information)"

requirements-completed: [CONV-01]

# Metrics
duration: 5min
completed: 2026-06-14
---

# Phase 01 Plan 01: Build Scaffolding & Agent Semconv Summary

**agent-tracer Gradle multi-module build (agent-semconv + backend) plus the vendored gen_ai.*/unityinflow.* attribute-key constants and a typed GenAiOperation enum — the machine-readable surface of the span convention (CONV-01).**

## Performance

- **Duration:** ~5 min
- **Started:** 2026-06-14T09:04:20Z
- **Completed:** 2026-06-14T09:09:18Z
- **Tasks:** 2
- **Files modified:** 14 created

## Accomplishments
- Two-module Gradle build (`agent-semconv`, `backend`) with version catalog, JVM 21 toolchain, and `./gradlew test` green from a cold start
- `agenttracer.publishing` convention plugin (renamed from kore's), retargeted to github.com/UnityInFlow/agent-tracer, signing the publication and skipping gracefully when env vars are absent
- `agent-semconv` ships `GenAiAttributes` (16 gen_ai.* + service.name keys), `UnityInFlowAttributes` (9 unityinflow.* keys), and `GenAiOperation` with a null-returning `fromOtel` factory — the only place attribute key literals appear
- TDD cycle for Task 2: failing tests committed (RED) before the implementation (GREEN)

## Task Commits

1. **Task 1: Scaffold the Gradle multi-module build** - `b0b498b` (chore)
2. **Task 2 (RED): Failing tests for constants + GenAiOperation** - `bbafab3` (test)
3. **Task 2 (GREEN): Vendor attribute constants + GenAiOperation enum** - `b67485e` (feat)

**Plan metadata:** _(final docs commit — see git log)_

## Files Created/Modified
- `settings.gradle.kts` - rootProject `agent-tracer`; includes agent-semconv + backend
- `build.gradle.kts` - root plugins, subprojects (group io.github.unityinflow, 0.0.1-SNAPSHOT), nmcp aggregation of agent-semconv only
- `gradle/libs.versions.toml` - version catalog (Kotlin 2.4.0, JUnit 5.14.4, Kotest 6.1.11, opentelemetry-proto 1.10.0-alpha, protobuf-java 4.35.1, kotlinter, nmcp)
- `gradle/wrapper/gradle-wrapper.properties` - Gradle 9.5.1 with verified SHA-256
- `buildSrc/build.gradle.kts` - kotlin-dsl plugin + literal nmcp 1.4.4 coordinate
- `buildSrc/src/main/kotlin/agenttracer.publishing.gradle.kts` - signed Maven Central publishing convention plugin
- `agent-semconv/build.gradle.kts` - published module, test-only deps (no OTLP dependency)
- `backend/build.gradle.kts` - internal module depending on agent-semconv + opentelemetry-proto
- `agent-semconv/.../GenAiAttributes.kt` - 16 gen_ai.* + service.name key constants
- `agent-semconv/.../UnityInFlowAttributes.kt` - 9 unityinflow.* extension key constants
- `agent-semconv/.../GenAiOperation.kt` - operation enum + fromOtel factory
- `agent-semconv/.../GenAiOperationTest.kt`, `AttributeKeysTest.kt` - JUnit 5 + Kotest tests
- `.gitignore` - Gradle/IDE/Kotlin/secrets ignores (keeps gradle-wrapper.jar)

## Decisions Made
- Aggregate only `agent-semconv` for Maven Central; `backend` extractor stays internal in v0.0.1 (D-03)
- STACK.md pins are authoritative over kore's older catalog (Kotlin 2.4.0, Gradle 9.5.1, JUnit 5.14.4)
- `opentelemetry-proto` lives only in the backend module so agent-semconv stays a near-zero-dep KORE-facing artifact

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Created .gitignore for build artifacts**
- **Found during:** Task 1 (after first `./gradlew test`)
- **Issue:** No `.gitignore` existed; `.gradle/`, `build/`, and `.kotlin/` would be committed as generated noise
- **Fix:** Added `.gitignore` mirroring the kore-runtime pattern (ignores build output, keeps `gradle-wrapper.jar`, ignores secrets/`.env`)
- **Files modified:** .gitignore
- **Verification:** `git status` shows only source/build-config files tracked; build dirs untracked
- **Committed in:** b0b498b (Task 1 commit)

**2. [Rule 1 - Bug] Removed `opentelemetry-proto` token from agent-semconv build comment**
- **Found during:** Task 1 verification
- **Issue:** Acceptance criterion `grep -rn 'opentelemetry-proto' agent-semconv/build.gradle.kts` requires zero matches; a KDoc-style comment contained the literal token, tripping the check
- **Fix:** Reworded the comment to "the OTLP proto types" — the module still has no such dependency
- **Files modified:** agent-semconv/build.gradle.kts
- **Verification:** grep returns 0 matches; `./gradlew test` still green
- **Committed in:** b0b498b (Task 1 commit)

**3. [Rule 1 - Bug] Removed bare `gen_ai.` tokens from GenAiOperation KDoc**
- **Found during:** Task 2 verification
- **Issue:** Acceptance criterion requires no `gen_ai.` literal outside GenAiAttributes.kt; two KDoc prose references in GenAiOperation.kt tripped the grep (false positives, not key literals)
- **Fix:** Reworded the KDoc to reference `[GenAiAttributes.OPERATION_NAME]` and "operation-name string" instead of the bare token
- **Files modified:** agent-semconv/.../GenAiOperation.kt
- **Verification:** grep returns NONE; `./gradlew :agent-semconv:test lintKotlin` green
- **Committed in:** b67485e (Task 2 GREEN commit)

---

**Total deviations:** 3 auto-fixed (1 blocking, 2 bug). **Impact on plan:** All necessary to satisfy acceptance criteria and avoid committing generated artifacts. No scope creep.

## Issues Encountered
- First build downloaded Gradle 9.5.1 (~1 min cold start); subsequent builds run in under 1 s. No functional issues.

## User Setup Required
None - no external service configuration required. (Maven Central publishing needs `SIGNING_KEY`/`SIGNING_PASSWORD`/`SONATYPE_*` env vars at release time only; the build skips signing gracefully without them.)

## Known Stubs
None - both modules are functional. The `backend` module has empty source dirs by design; it is populated by Plan 02 (domain model + extractor).

## Next Phase Readiness
- Build, version catalog, and publishing plugin ready for Plan 02 (AgentSpan domain model + AgentSpanExtractor in the backend module)
- CONV-01 machine surface (attribute key constants + operation enum) complete and tested — Plan 02 references these constants
- No blockers

## Self-Check: PASSED

All 12 created files verified present on disk; all 3 task commits (b0b498b, bbafab3, b67485e) verified in git history.

---
*Phase: 01-attribute-convention-domain-model*
*Completed: 2026-06-14*
