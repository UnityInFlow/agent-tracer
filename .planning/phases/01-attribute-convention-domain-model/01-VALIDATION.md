---
phase: 1
slug: attribute-convention-domain-model
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-06-13
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit Jupiter 5.14.4 (runner) + Kotest assertions-core 6.1.11 (matchers) |
| **Config file** | none yet — created in Wave 0 (`backend/build.gradle.kts` test block, `useJUnitPlatform()`) |
| **Quick run command** | `./gradlew :backend:test --tests "*Extractor*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~sub-second (pure functions; no I/O, no containers) |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :backend:test --tests "*Extractor*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green + `ktlintCheck` clean
- **Max feedback latency:** ~5 seconds (quick run is sub-second; full suite small)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| W0 | build | 0 | — | — | N/A | infra | `./gradlew test` (empty green) | ❌ W0 | ⬜ pending |
| CONV-01 | convention | — | CONV-01 | — | N/A | doc/structural + constants exist | `./gradlew :agent-semconv:test` | ❌ W0 | ⬜ pending |
| CONV-02a | extractor | — | CONV-02 | — | gen_ai-only span → typed agent fields, zero `unityinflow.*` needed | unit | `./gradlew :backend:test --tests "*GenAiOnly*"` | ❌ W0 | ⬜ pending |
| CONV-02b | extractor | — | CONV-02 | — | `invoke_agent`/`execute_tool` parsed into enum | unit | `./gradlew :backend:test --tests "*Operation*"` | ❌ W0 | ⬜ pending |
| CONV-03a | extractor | — | CONV-03 | — | OTLP value type preserved (int→Long, double→Double, bool→Boolean) | unit | `./gradlew :backend:test --tests "*ValueType*"` | ❌ W0 | ⬜ pending |
| CONV-03b | model | — | CONV-03 | — | Payload cap/truncation represented (`payloadTruncated`, original size) | unit | `./gradlew :backend:test --tests "*Payload*"` | ❌ W0 | ⬜ pending |
| SC-4 | extractor | — | CONV-02/03 (SC-4) | — | Precedence: `unityinflow.*` overrides `gen_ai.*` on conflict | unit | `./gradlew :backend:test --tests "*Precedence*"` | ❌ W0 | ⬜ pending |
| D-04 | extractor | — | CONV-03 (D-04) | — | `saturation_pct` clamps to [0,100] | unit | `./gradlew :backend:test --tests "*Clamp*"` | ❌ W0 | ⬜ pending |
| D-12 | extractor | — | CONV-02/03 (D-12) | — | missing field → null (no sentinel) | unit | `./gradlew :backend:test --tests "*Nullable*"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `settings.gradle.kts` + `gradle/libs.versions.toml` (version catalog) — no build exists yet (greenfield)
- [ ] `agent-semconv/build.gradle.kts` and `backend/build.gradle.kts` with `useJUnitPlatform()`, Kotest assertions-core + JUnit Jupiter deps
- [ ] Test fixtures: a **golden gen_ai-only span** (no `unityinflow.*`) and a **full `unityinflow.*` span** as Kotlin builders or JSON, reused across tests
- [ ] No framework install needed beyond declaring the already-verified test deps (JUnit 5.14.4 + Kotest 6.1.11)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Convention doc is human-readable and complete | CONV-01 | Prose readability is subjective; structural lint covers attribute/type presence but not clarity | Open `docs/agent-span-convention.md`; confirm every v1.0 attribute has name + OTLP type + description, and payload-size rules are stated |

*All behavioral/extraction logic has automated verification; only doc readability is manual.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 5s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
