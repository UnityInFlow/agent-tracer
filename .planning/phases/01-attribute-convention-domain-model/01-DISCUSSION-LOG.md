# Phase 1: Attribute Convention & Domain Model - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-12
**Phase:** 1-Attribute Convention & Domain Model
**Areas discussed:** Convention publishing & versioning, Attribute vocabulary details, Run identity & outcome semantics, Extraction precedence & payload rules

---

## Convention publishing & versioning

| Option | Description | Selected |
|--------|-------------|----------|
| docs/ in this repo | Versioned markdown spec in agent-tracer — the tool that owns the contract hosts it | ✓ |
| Separate spec repo | Standalone UnityInFlow/agent-semconv repo — more ceremony | |
| KDoc only | Constants-only, no prose spec | |

| Option | Description | Selected |
|--------|-------------|----------|
| Semver doc + schema_version attribute | Doc carries semver; emitters stamp unityinflow.schema_version | ✓ |
| Doc version only | No version on the wire | |

| Option | Description | Selected |
|--------|-------------|----------|
| Published constants artifact | agent-semconv module with typed attribute keys; KORE depends on it | ✓ |
| Doc only | Emitters hardcode strings | |

**User's choice:** All recommended options.

---

## Attribute vocabulary details

| Option | Description | Selected |
|--------|-------------|----------|
| 0–100 percent double | Matches _pct suffix; extractor clamps | ✓ |
| 0.0–1.0 ratio | Would require renaming the attribute | |

| Option | Description | Selected |
|--------|-------------|----------|
| USD double (unityinflow.cost.usd) | One attribute, matches LLM pricing pages | ✓ |
| Micro-USD integer | Billing-grade precision, overkill for tracing | |
| Amount + currency pair | Two attributes for marginal v1 value | |

| Option | Description | Selected |
|--------|-------------|----------|
| gen_ai.usage.* only | Standard semconv tokens, no duplicates | ✓ |
| Add unityinflow.tokens.* | Isolation from semconv churn at cost of redundancy | |

| Option | Description | Selected |
|--------|-------------|----------|
| Plan step + skill version | unityinflow.plan.step + unityinflow.skill.version in v1.0 | ✓ |
| Minimal core only | Four attributes only | |
| Broader set | Reserved vocabulary for future tools | |

**User's choice:** All recommended options.

---

## Run identity & outcome semantics

| Option | Description | Selected |
|--------|-------------|----------|
| Trace = run | Root span is the run entry point; matches agent_runs MV design | ✓ |
| Explicit run-id attribute | Multi-run traces, added wire complexity | |

| Option | Description | Selected |
|--------|-------------|----------|
| Root span status + optional override | OTel status default; unityinflow.outcome (success/failure/partial/cancelled) overrides | ✓ |
| Span status only | Cannot express partial | |
| Explicit attribute required | Generic sources would show unknown | |

| Option | Description | Selected |
|--------|-------------|----------|
| gen_ai.agent.name, service.name fallback | Generic sources get a usable agent dimension | ✓ |
| gen_ai.agent.name only | Strict, unknown-agent runs | |
| Own attribute | Duplicates a standard for no gain | |

**User's choice:** All recommended options.

---

## Extraction precedence & payload rules

| Option | Description | Selected |
|--------|-------------|----------|
| unityinflow.* wins | Specific extension over generic standard; test-verified | ✓ |
| gen_ai.* wins | Makes the extension pointless on overlap | |

| Option | Description | Selected |
|--------|-------------|----------|
| Nullable typed fields | Absence is information; aggregates skip nulls | ✓ |
| Sentinel defaults | 0-cost-because-missing corrupts rollups | |

| Option | Description | Selected |
|--------|-------------|----------|
| Cap + truncate marker | 256 KB per payload field; truncated flag + original size | ✓ |
| No cap | Pathological spans bloat rows | |
| Hash beyond cap | No blob store in v0.0.1 to resolve hashes | |

| Option | Description | Selected |
|--------|-------------|----------|
| Standard gen_ai events/attributes | Generic sources' payloads work in replay too | ✓ |
| Own unityinflow.payload.* | Generic payloads wouldn't show in replay | |

**User's choice:** All recommended options.

---

## Claude's Discretion

- Gradle module layout, domain model class design, extractor API shape, test structure
- Prose structure of the convention document
- Vendored gen_ai constants vs upstream `opentelemetry-semconv` artifact (research to verify)

## Deferred Ideas

- OpenInference attribute mapping → v2 (INGEST-04)
- Cross-trace subagent linkage via span links → v2 (INGEST-05)
- Hash-based payload deduplication → needs blob store, post-v0.0.1
