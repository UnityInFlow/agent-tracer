# Phase 3: OTLP Ingestion - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-22
**Phase:** 3-OTLP Ingestion
**Areas discussed:** Backpressure response policy, partial_success & rejection, Generic (non-agent) spans, Bootstrap & lifecycle

---

## Backpressure response policy (SC3)

| Option | Description | Selected |
|--------|-------------|----------|
| Fail-fast retryable | On `isSaturated`, return gRPC `RESOURCE_EXHAUSTED` / HTTP 429 + Retry-After; otherwise `send()` (brief suspension OK) | ✓ |
| Always suspend (pure backpressure) | Never reject early; let `send()` suspend until drain | |
| Bounded wait then 429 | Wait up to timeout T, then return retryable status | |

**User's choice:** Fail-fast retryable
**Notes:** Spec-idiomatic — OTel SDK/Collector clients retry RESOURCE_EXHAUSTED/429 with backoff; frees server resources vs. holding requests open. → CONTEXT D-01.

---

## OTLP partial_success / span rejection semantics (SC3)

| Option | Description | Selected |
|--------|-------------|----------|
| Tolerant per-span | Accept every mappable span; count in `rejected_spans` only when a span can't become a valid row; truncate oversized payloads, never reject; transport error only when whole body undecodable | ✓ |
| Strict whole-request | Any malformed/unmappable span fails the entire export | |
| Never reject (best-effort) | Always accept, silently drop unmappable spans, never populate partial_success | |

**User's choice:** Tolerant per-span
**Notes:** One bad span never fails a batch; good interop with heterogeneous SDKs; reuses Phase-2 256 KB truncation. → CONTEXT D-02.

---

## Generic (non-agent) spans (SC1)

| Option | Description | Selected |
|--------|-------------|----------|
| Store all, unified path | Extractor yields AgentSpan with agent columns null for non-agent spans; raw attrs preserved in maps; no drop, no separate table | ✓ |
| Agent spans only | Store only convention-matching spans; drop generic | |

**User's choice:** Store all, unified path
**Notes:** Satisfies SC1 literally for stock-SDK traffic; keeps surrounding context spans for debugging. → CONTEXT D-03.

---

## App bootstrap & lifecycle

| Option | Description | Selected |
|--------|-------------|----------|
| Migrate-then-serve + drain | `main`: config → migrations before ports → SpanWriter → Ktor + gRPC → shutdown hook closes ports, `drainAndClose()`, close CH client | ✓ |
| Serve immediately, migrate async | Open ports first, migrate lazily/in background | |
| Ports-first, no explicit drain | Start servers, rely on small in-flight buffer at shutdown | |

**User's choice:** Migrate-then-serve + drain
**Notes:** Schema failures surface at boot (the Code-62/549 class fixed in Phase 2), not at request time; no spans lost on `compose down`. Manual wiring, no Spring DI (Ktor-first rule). → CONTEXT D-04, D-05.

---

## Claude's Discretion

- Package layout under `…/agenttracer/ingest/`, exact env-var names, default gRPC max inbound size (size for prompt-heavy spans, justify vs SC2's >4 MB), Retry-After value, and test structure (JUnit 5 + Kotest + Testcontainers).
- Transport-agnostic shared decoder (D-06) and SpanContext identity encoding following the Phase-2 contract (D-07).

## Deferred Ideas

- INGEST-04 OpenInference attribute mapping — future phase.
- INGEST-05 cross-trace subagent linkage via span links — deferred until KORE's spawning model is concrete.
- OTLP/HTTP JSON encoding (protobuf only for v1); OTLP logs/metrics signals (traces-only this milestone).
