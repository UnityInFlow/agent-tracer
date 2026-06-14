---
phase: 01-attribute-convention-domain-model
plan: 03
subsystem: docs
tags: [convention, otlp, gen-ai-semconv, documentation, contract, semver]

# Dependency graph
requires:
  - "01-01: agent-semconv (GenAiAttributes/UnityInFlowAttributes) — the canonical attribute key strings the doc table mirrors"
provides:
  - "docs/agent-span-convention.md v1.0 — the published, versioned public contract KORE and OTel emitters are built against"
  - "README.md — project entry point linking the convention contract"
  - "Documentary CONV-01 (attribute vocabulary published) + CONV-03 (per-attribute OTLP value types + payload rules) surface"
affects: [03-otlp-ingestion, 04-query-api-compose, 05-frontend, kore-instrumentation]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Convention published as a versioned markdown contract in-repo (D-01); semver 1.0 with unityinflow.schema_version stamp (D-02)"
    - "Single attribute contract table with an OTLP value-type column is the schema source of truth (CONV-03)"
    - "Doc attribute keys mirror the agent-semconv constants verbatim — no second source of key strings"

key-files:
  created:
    - docs/agent-span-convention.md
    - README.md
  modified: []

key-decisions:
  - "README created from scratch (none existed) per CLAUDE.md every-tool-needs-a-README rule; minimal title + description + convention link"
  - "gen_ai.system appears only as rename-precedent prose, never as a contract-table key — it has no agent-semconv constant and is intentionally excluded from the contract"

patterns-established:
  - "The convention doc is the public API surface: every key string matches GenAiAttributes/UnityInFlowAttributes exactly; future renames are a one-file change in agent-semconv mirrored here"

requirements-completed: [CONV-01, CONV-03]

# Metrics
duration: ~3min
completed: 2026-06-14
---

# Phase 01 Plan 03: Convention Document Summary

**Published `docs/agent-span-convention.md` v1.0 — the ecosystem's versioned public contract: a single attribute table with a per-attribute OTLP value-type column, the three `unityinflow.*` extensions (skill.name, context.saturation_pct, cost.usd) layered over standard `gen_ai.*`, tokens-only-from-gen_ai statement, run/outcome/precedence semantics, 256 KB payload cap with truncation signalling, and the OTel PII warning carried forward; README links to it.**

## Performance

- **Duration:** ~3 min
- **Started:** 2026-06-14T09:21:31Z
- **Completed:** 2026-06-14
- **Tasks:** 1
- **Files created:** 2

## Accomplishments

- `docs/agent-span-convention.md` (190 lines) with all 7 required sections from RESEARCH "Convention Document Contents":
  1. Versioning & status — semver 1.0.0, `unityinflow.schema_version` stamp, superset-of-OTel-GenAI statement, targeted upstream semconv named, `gen_ai.system`→`gen_ai.provider.name` rename precedent.
  2. Single attribute contract table — `Attribute key | OTLP value type | Req. | Units/scale | Notes`, split into the standard `gen_ai.*` rows and the `unityinflow.*` extension/reserved rows. The OTLP value-type column satisfies CONV-03.
  3. The three `unityinflow.*` extensions + reserved set (skill.version, plan.step, outcome, schema_version, payload.truncated, payload.original_size_bytes).
  4. Tokens-only statement (D-06) — explicit "no `unityinflow.*` token attribute".
  5. Run identity & outcome semantics (D-08/09/10) — trace=run, root span=entry point, outcome from OTel status unless `unityinflow.outcome` overrides, agent identity = `gen_ai.agent.name` else `service.name`, classify by `gen_ai.operation.name` not span name.
  6. Payload handling rules (D-13/14) — standard `gen_ai.*.messages` source keys only, 256 KB cap, receiver-side truncation with `unityinflow.payload.truncated` + `unityinflow.payload.original_size_bytes`.
  7. Extraction precedence (D-11) — `unityinflow.*` wins on conflict.
  - Plus a Typed-Extraction value-type mapping table and a Security/sensitive-data section carrying OTel's PII warning, payload-opt-in/redactable guidance, and the untrusted-attribute-values note for the Phase 5 UI.
- `README.md` created (none existed) with title, one-line description, and a markdown link to the convention.
- Every attribute key string in the doc matches the `agent-semconv` constants from Plan 01 — no key drift.

## Task Commits

1. **Task 1: Write the versioned convention document + README link** - `974d962` (docs)

**Plan metadata:** _(final docs commit — see git log)_

## Files Created

- `docs/agent-span-convention.md` - the published v1.0 agent span attribute convention contract
- `README.md` - project README linking the convention

## Decisions Made

- **README created from scratch:** no README existed; per CLAUDE.md ("every tool must have a README.md") a minimal one was created with the required convention link. Kept intentionally small — it will grow as later phases ship features.
- **`gen_ai.system` is prose-only:** it appears solely as the rename-precedent example justifying the `schema_version` stamp; it is deliberately NOT a contract-table key (it has no agent-semconv constant — the live key is `gen_ai.provider.name`). This is not key drift.

## Deviations from Plan

None - plan executed exactly as written. The single task ran end to end; the plan's verify command printed `CONVENTION_OK` and all acceptance criteria passed on the first attempt.

## Issues Encountered

- `wc -l` reported 0 because the file's final byte is not a newline; `grep -c ''` confirms 190 content lines. No functional issue — every required grep matched.

## User Setup Required

None - documentation only, no external service configuration.

## Known Stubs

None - the convention document is complete and self-contained.

## Threat Flags

None. This plan satisfies the documentary mitigation for T-01-07 (the doc carries OTel's sensitive-data/PII warning, states payloads are opt-in and redactable/truncatable at the emitter, and defines the 256 KB cap) and documents T-01-08 (attribute values are untrusted strings the Phase 5 UI must escape). No new security surface introduced (markdown + README only, no package installs — T-01-SC accept).

## Next Phase Readiness

- The published contract is the documented promise KORE instrumentation and any OTel emitter are built against; the per-attribute OTLP value-type column is the schema contract Phase 2's ClickHouse columns inherit.
- CONV-01 (vocabulary published) and CONV-03 (value types + payload rules documented) documentary surface is complete; the extraction logic (CONV-02/03) shipped in Plan 02.
- Phase 1 (attribute convention & domain model) is functionally complete across Plans 01–03.
- No blockers.

## Self-Check: PASSED

- `docs/agent-span-convention.md` present on disk (190 lines).
- `README.md` present on disk with the `agent-span-convention.md` link.
- Task commit `974d962` verified in git history.
- Plan verify command prints `CONVENTION_OK`.

---
*Phase: 01-attribute-convention-domain-model*
*Completed: 2026-06-14*
