---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: Phase 3 context gathered
last_updated: "2026-06-22T18:51:00.015Z"
last_activity: 2026-06-22 -- Phase 03 planning complete
progress:
  total_phases: 5
  completed_phases: 2
  total_plans: 9
  completed_plans: 9
  percent: 40
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-06-12)

**Core value:** A developer debugging an agent failure can see the full decision tree of any agent run — with token cost and context saturation on every node — instead of reading thousands of generic spans.
**Current focus:** Phase 02 — clickhouse-storage-layer

## Current Position

Phase: 3
Plan: Not started
Status: Ready to execute
Last activity: 2026-06-22 -- Phase 03 planning complete

Progress: [████░░░░░░] 40%

## Performance Metrics

**Velocity:**

- Total plans completed: 10
- Average duration: ~5 min
- Total execution time: ~0.1 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 | 4 | - | - |
| 02 | 5 | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*
| Phase 01 P02 | 9min | 2 tasks | 12 files |
| Phase 01 P03 | 3min | 1 tasks | 2 files |
| Phase 01 P04 | 2 min | 2 tasks | 5 files |
| Phase 02 P01 | ~4 min | 2 tasks | 5 files |
| Phase 02 P02 | 35min | 3 tasks | 1 files |
| Phase 02 P03 | ~22 min | 3 tasks | 7 files |
| Phase 02 P04 | ~10 min | 3 tasks | 8 files |
| Phase 02 P05 | ~25 min | 2 tasks | 7 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Roadmap]: Convention-first build order — the attribute convention is artifact #1; five of ten acceptance criteria sit downstream of it
- [Roadmap]: DIST-01 (Docker Compose all-in-one) delivered in Phase 4 with the backend milestone, not a standalone packaging phase — cold-start ingest is provable with curl before React exists
- [Roadmap]: Server-side DAG assembly — the frontend receives a render-ready nested tree; DAG correctness stays inside Kotlin test coverage
- [Research]: Use opentelemetry-proto pre-generated gRPC stubs (no codegen pipeline); grpc-netty-shaded alongside Ktor; ClickHouse client-v2 (not JDBC); elkjs (not dagre/d3-dag) for compound-node DAG layout
- [01-01]: agent-semconv published; backend extractor stays internal in v0.0.1 (D-03) — only agent-semconv aggregated for Maven Central
- [01-01]: STACK.md pins win over kore's older catalog (Kotlin 2.4.0, Gradle 9.5.1, JUnit 5.14.4)
- [01-01]: opentelemetry-proto confined to backend module so agent-semconv stays a near-zero-dep KORE-facing artifact
- [01-02]: Reuse agentsemconv.GenAiOperation as AgentSpan.operation; no duplicate domain enum (single source of truth)
- [01-02]: Strict OTLP coercion (Q2) — wrong AnyValue variant returns null, never lexically parses
- [Phase ?]: Convention published as versioned markdown contract (docs/agent-span-convention.md v1.0); doc keys mirror agent-semconv constants verbatim
- [Phase 01]: Fixed WR-01/UAT gap by extending AgentSpan to match the published gen_ai.* contract — Add four nullable fields + extractor reads rather than downgrade the Stable doc (D-12, no sentinel; gen_ai.* only, no unityinflow.* precedence)
- [02-01]: kotlin-logging 8.0.4 / logback 1.5.34 confirmed latest published on Maven Central (research A2 [ASSUMED] resolved) — no patch substitution
- [02-01]: ClickHouseTestSupport is a plain `object` with `by lazy` container (JVM-wide reuse, Ryuk teardown) instead of JUnit `@Container @JvmStatic` — true cross-IT-class reuse, ~90s spin-up paid once
- [02-01]: SpanContext is the envelope-identity seam (traceId/spanId/parentSpanId/timestamp) AgentSpan omits — fixtures mint it in Phase 2, the OTLP receiver populates it in Phase 3
- [Phase ?]: Plan 02-02: committed ClickHouseSchemaIT despite a Testcontainers HTTP-wait Read-timeout under local Colima/QEMU (container healthy via manual curl); recorded as a verification gap to confirm green on native-Docker CI runners — did not fake a pass.
- [02-03]: SpanRow/PayloadRow are mutable `var` plain classes (not data classes) — client-v2 0.9.8 POJOSerDe binds DDL columns to JavaBean getters/setters and needs a no-arg ctor on the read path; mutability isolated to the row classes + documented (sanctioned var-rule exception). AgentRunRow stays an immutable data class (read-only).
- [02-03]: Repository is the sole ClickHouse-SQL holder; every user value bound as a {name:Type} placeholder (T-02-06), writes are typed RowBinary POJO inserts; FINAL only on aggregating tables, never the hot spans scan.
- [02-03]: Committed SpanRepositoryIT despite the same Testcontainers HTTP-wait gap under Colima/QEMU (container healthy via manual curl /ping → HTTP 200); did not fake a pass — runs green on native-Docker CI.
- [02-04]: SpanWriter intake is a BOUNDED Channel(capacity) carrying immutable (AgentSpan, SpanContext) envelopes; send suspends on full (= backpressure, never drops, never UNLIMITED) — T-02-10. Single writer coroutine flushes by count/time via select{}+onTimeout; failed flush retries with bounded exponential backoff (delay, never Thread.sleep) then retains the batch (carried forward) + asserts saturated. drainAndClose re-flushes the tail (no loss on shutdown).
- [02-04]: 256 KB-per-field payload cap measured in encoded UTF-8 BYTES, truncated on a char boundary (CharsetDecoder IGNORE → no mid-codepoint split); the larger of (emitter-reported, locally-measured) original size is preserved (D-13, no double-truncate).
- [02-04]: Added kotlinx-coroutines-test (testImplementation, same group/version as coroutines-core 1.11.0) for runTest/virtual-time flush-by-time assertions — first-party JetBrains companion, not a slopcheck-gated install.
- [02-04]: PartCountIT (SC4) committed but unobserved-passing locally — identical Testcontainers HTTP-wait read-timeout under Colima/QEMU (initializationError, class never instantiates); container proven healthy via manual docker run + curl /ping → HTTP 200 in ~5ms. SpanWriterTest (pure JVM) exercises the same send→flush→writeBatch path and passes. Runs green on native-Docker CI.
- [02-05]: Gap closure proved the schema applies against REAL ClickHouse on Docker Desktop (the Colima/QEMU context masked every storage bug all phase). Root blocker: splitter split on `;` before stripping `--` comments (Code 62). Secondary defects fixed in the same green commit: Code 549 (SimpleAggregateFunction columns illegal in key/TTL/PARTITION → added plain DateTime key columns), JUnit silently skipping expression-body @Test methods until `: Unit` is declared (this masked verification), getString 1-based fix, PartCountIT TTL-window fixture re-anchor. Full suite 65/65 green, 0 skipped.
- [02-05 / D-07 Option A]: agent_runs rollup test asserts genuine Phase-2 math over 3 distinct spans; duplicate/late/out-of-order span dedup deferred to Phase 4. No span dedup implemented; spans/agent_runs engine choice unchanged (Option B rejected).

### Pending Todos

None yet.

### Blockers/Concerns

- [Phase 2]: OTel Collector ClickHouse exporter DDL referenced but not fetched in depth — pull actual schema during Phase 2 planning (research flag)
- [Phase 4]: Replay/assembly design (late/orphaned spans, step ordering, clock skew) flagged for deeper research during phase planning
- [Phase 5]: elkjs compound layout in a Web Worker is MEDIUM-confidence — validate with an early spike
- [General]: Saturation timeline is emitter-gated — zero value until something emits `context_saturation_pct`; demo emitter (Phase 4) is the mitigation
- [02-05] RESOLVED (2026-06-22): Rollup dedup decision resolved via Option A — agentRunsRollupIsCorrect now asserts honest Phase-2 rollup of 3 distinct spans (no duplicate re-insert); duplicate/late/out-of-order dedup stays deferred to Phase 4 per D-07. Splitter Code 62 + schema Code 549 + PartCountIT TTL + JUnit silently-skipped-expression-body (`: Unit`) + getString 1-based all fixed. Full backend suite 65/65 green (0 skipped) against real ClickHouse on Docker Desktop. UAT blocker closed.

## Deferred Items

Items acknowledged and carried forward from previous milestone close:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| *(none)* | | | |

## Session Continuity

Last session: 2026-06-22T17:54:29.795Z
Stopped at: Phase 3 context gathered
Resume file: .planning/phases/03-otlp-ingestion/03-CONTEXT.md
