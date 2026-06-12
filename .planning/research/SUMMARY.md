# Project Research Summary

**Project:** agent-tracer (Tool 09, UnityInFlow ecosystem)
**Domain:** Agent-native trace visualization — OTLP receiver + ClickHouse storage + React/D3 DAG visualizer
**Researched:** 2026-06-12
**Confidence:** HIGH

## Executive Summary

agent-tracer is a purpose-built agent observability tool sitting deliberately between three existing camps: generic distributed tracers (Jaeger/Tempo — OTLP-native but zero agent semantics), full LLMOps platforms (Langfuse/LangSmith/Phoenix — tracing buried in eval/prompt-management suites with heavy self-host footprints), and SaaS agent-ops tools (AgentOps — closest in vision but SDK-coupled and not self-hostable). Every successful ClickHouse-backed tracer (SigNoz, Jaeger v2, Uptrace) converges on the same four-layer shape: **receiver → batching write pipeline → wide columnar table + materialized views → query service → SPA**. agent-tracer is a deliberately smaller, agent-native version of exactly this — with collector and query service collapsed into one Kotlin/Ktor process and a single ClickHouse container as the only other moving part.

The recommended approach is convention-first: the published agent span attribute convention (a `gen_ai.*` superset adding `unityinflow.cost.usd` and `unityinflow.context.saturation_pct`, which OTel does not standardize) is the project's public contract and must be the first artifact. Five of the ten acceptance criteria sit downstream of it, and mapping standard `gen_ai.*` attributes at the extractor level is what makes "any OTel-compatible source" true for visualization, not just ingest. The backend builds in strict dependency order (domain/convention → ClickHouse schema → OTLP receivers → query API with server-side DAG assembly) and must be fully testable with curl before any React exists, per the ecosystem rule. The strongest launch differentiator confirmed by research: **no major competitor ships a first-class context-saturation timeline** — this is a recognized 2026 best practice with zero tooling.

The key risks are all ClickHouse and protocol discipline, and all preventable at design time: per-request inserts cause "too many parts" collapse (batching is day-one architecture, not optimization); wrong ORDER BY requires a full table rewrite to fix (use the documented two-table pattern with a trace_id materialized view); agent attributes buried in a generic Map column destroy the cost-filter performance the product is premised on (promote them to typed columns); and OTLP protocol semantics (4 MB gRPC limit, partial_success, backpressure status codes) must be conformance-tested with real OTel SDK senders, not hand-rolled payloads. On the frontend, force-directed D3 layout is wrong for this domain — use elkjs (the only maintained JS layout engine with compound-node support, needed for nested subagent DAGs) for layout math with D3/React rendering only.

## Key Findings

### Recommended Stack

Backend: Kotlin 2.4.0 / Ktor 3.5.0 (Netty) serving the OTLP/HTTP endpoint, query REST API, and SPA statics, with a standalone `io.grpc.Server` (grpc-netty-shaded 1.82.0) in the same JVM for OTLP/gRPC on :4317. Key verified finding: `io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha` ships pre-generated `TraceServiceGrpc` stubs — **no protoc/codegen pipeline needed** (the `-alpha` suffix is a permanent publishing convention; OTLP v1 trace proto is stable). ClickHouse access via the official `client-v2` 0.9.8 (not JDBC) for RowBinary streaming batch inserts, against ClickHouse 26.3 LTS pinned in Compose.

Frontend: React 19 + D3 7.9 with **elkjs 0.11** as the DAG layout engine run in a Web Worker — the deciding factor is elkjs's native compound/nested-node support, exactly matching the subagent-hierarchy criterion (dagre and d3-dag cannot do this). Zustand 5 for UI state, TanStack Query 5 for server state, Vite 8 + TypeScript 6 + Vitest 4.

**Core technologies:**
- Kotlin 2.4.0 + Ktor 3.5.0 (Netty): single backend process — OTLP/HTTP, query API, SPA serving
- grpc-netty-shaded 1.82.0 + opentelemetry-proto 1.10.0-alpha: OTLP/gRPC receiver via pre-generated stubs (shaded Netty avoids classpath conflict with Ktor)
- ClickHouse 26.3 LTS + client-v2 0.9.8: trace storage with streaming batch inserts
- React 19 + D3 7.9 + elkjs 0.11: elkjs computes layout (compound nodes for subagent nesting), D3/React render
- Testcontainers (ClickHouse) + JUnit 5.14 + Kotest 6 assertions: ClickHouse SQL is too dialect-specific to mock

**Avoid:** kotlinx-rpc gRPC (development preview), `opentelemetry-sdk` as a receiver (it emits, it doesn't receive — the old CLAUDE.md dependency sketch is a category error), original `dagre` (unmaintained since 2019), ClickHouse JDBC v1/legacy clients, `clickhouse:latest` tags.

### Expected Features

The 10 acceptance criteria are broadly correct for the market. Research recommends two additions and one scope-down.

**Must have (table stakes):**
- OTLP ingest, gRPC + HTTP/protobuf — the integration contract every tracer honors
- `gen_ai.*` semantic convention compatibility — the ecosystem (Jaeger v2, Datadog, Phoenix) has converged on it; without extractor-level mapping, non-KORE sources ingest but render as empty DAGs
- Trace list with filters (agent, model, cost, outcome, date) — universal expectation
- Span detail panel with full input/output payloads — the #1 debugging interaction in every competitor; **recommend adding as explicit criterion** (cheap once payloads are stored)
- Token/cost per span with parent roll-ups — LangSmith-style subtree sums are what users expect
- Docker Compose one-command self-host — first README command for OSS observability
- Sample-data/demo emitter — **recommend adding**; every criterion is undemonstrable without traces, and KORE instrumentation is out of scope

**Should have (competitive differentiators):**
- Context-saturation timeline — **no competitor ships this**; lead launch messaging with it (value gated on emitters supplying the attribute — demo emitter makes it demonstrable)
- Framework-agnostic agent DAG view with cost overlay — Langfuse's graph is LangGraph-coupled; ours is attribute-convention-driven
- Subagent hierarchy as collapsible nested DAG — falls out of `invoke_agent` span nesting
- Replay mode — keep but **scope minimally**: ordered step-through of stored spans + context viewer, no state reconstruction (highest slip risk in v0.0.1)
- Published attribute convention as a versioned spec — makes agent-tracer the reference implementation for the whole ecosystem

**Defer (v0.1+/v2):**
- Waterfall/duration view (v0.1 — the familiarity bridge for Jaeger refugees; same data, different layout)
- Permalinks, session grouping, analytics dashboard, trace comparison (v0.1.x)
- Helm chart, retention UI, OpenInference mapping, live streaming, auth/multi-tenancy (v0.2+)

**Anti-features (deliberately excluded):** evals/scorers (agent-bench owns this), prompt management (prompt-vc owns this), generic APM, proxy/gateway mode (observability must never gate agent traffic), per-framework SDK adapters (OTLP-only, no SDK treadmill), alerting (budget-breaker owns cost enforcement).

### Architecture Approach

Single Kotlin/Ktor process containing: OTLP receivers (gRPC + HTTP) → span normalizer/attribute extractor → bounded `Channel`-based batching write pipeline (flush by count ~5k or time ~2s) → ClickHouse repository → query API with **server-side DAG assembly** (frontend receives a render-ready nested tree; D3 only does layout/rendering). Storage is one wide MergeTree `spans` table with promoted typed agent columns (AgentName, Model, SkillName, InputTokens, OutputTokens, CostUsd, ContextSaturationPct) plus Map columns for everything else, and two materialized views: `trace_id_ts` (trace point-lookup) and `agent_runs` (per-trace rollup serving the entire run-list/filter screen without scanning spans). Single Gradle module backend + sibling frontend folder; package boundaries (`domain/`, `convention/`, `ingest/`, `storage/`, `query/`, `api/`) enforce layering.

**Major components:**
1. `convention/` + extractor — the published `gen_ai.*`-superset contract; pure Kotlin, zero I/O, highest test ROI; the only place gen_ai semconv churn is absorbed
2. Ingest pipeline — receivers never touch the DB; bounded channel gives natural backpressure (full buffer → OTLP `RESOURCE_EXHAUSTED`)
3. ClickHouse storage — versioned startup migrations, batch writer, repository interface (query layer never sees SQL)
4. Query API + DAG assembler — tree from `parent_span_id`, subagent nesting via `invoke_agent`, subtree cost rollups; replay is a view over the same payload ordered by start time
5. React SPA — run list/filters, elkjs+D3 DAG, saturation timeline, replay stepper; served as statics by Ktor

### Critical Pitfalls

1. **Per-request ClickHouse inserts → "too many parts" death spiral** — batching channel from day one; retrofitting changes the backpressure contract. Verify: sustained 100 exports/s, `system.parts` bounded.
2. **Wrong ORDER BY/partitioning** — fixing later requires a full table rewrite. Use `ORDER BY (ServiceName, SpanName, toUnixTimestamp(Timestamp), TraceId)` + `trace_id_ts` MV + bloom filter; partition by day with TTL in DDL from v0.0.1.
3. **Agent attributes buried in the Map column** — cost filters and overlays (the product premise) degrade with volume. Promote to typed columns at ingest, preserving OTLP value types (int → UInt64, not "1234").
4. **OTLP protocol semantics ignored** — raise gRPC max message size (16–32 MB; agent spans are fat), implement `partial_success`, return `UNAVAILABLE` on overload. Conformance-test with real OTel SDK + telemetrygen senders.
5. **Ingest-time trace assembly** — real spans arrive late, out of order, orphaned. Store immutable append-only; assemble at query time; render orphans under a synthetic parent with "incomplete" badges. Never `ALTER UPDATE/DELETE` — ReplacingMergeTree + TTL only.
6. **D3 force-directed SVG at scale** — deterministic layered layout (elkjs), collapse subagent subtrees by default, test with a generated 5,000-span trace before declaring the DAG done.
7. **Netty classpath conflict** — grpc-netty-shaded + grpc-bom pinning; separate ports (4317 gRPC, 4318+8080 Ktor).
8. **Giant prompt payloads inline** — dedicated ZSTD-compressed payload columns, excluded from list/DAG APIs, lazy-fetched only in replay; configurable truncation at ingest.
9. **Compose that only works on the author's machine** — named volume, ulimits, low-memory ClickHouse config, healthcheck + connection retry, idempotent migrations; CI cold-start smoke test on a memory-limited runner.

## Implications for Roadmap

Based on research, suggested phase structure (backend fully built/tested before frontend, per ecosystem rule):

### Phase 1: Domain Model + Attribute Convention + Extractor
**Rationale:** Five of ten acceptance criteria are downstream of the convention; it's the public contract KORE and the ecosystem adopt; pure Kotlin with zero infra = cheapest path to the >80% coverage requirement. Depends on nothing.
**Delivers:** `AgentSpan`/`AgentRun`/`DagNode` domain types; `docs/ATTRIBUTE-CONVENTION.md` published as a versioned `gen_ai.*` extension (`unityinflow.cost.usd`, `unityinflow.context.saturation_pct`, `unityinflow.skill.name`); priority-ordered attribute extractor mapping `gen_ai.usage.*`, `gen_ai.agent.name`, `gen_ai.operation.name`.
**Addresses:** Convention spec + gen_ai mapping (P1), agent-aware span attributes criterion.
**Avoids:** Pitfall 3 (typed extraction designed with the schema), gen_ai semconv churn (all key strings isolated in `convention/`).

### Phase 2: ClickHouse Schema + Storage Layer
**Rationale:** Schema mistakes (ORDER BY, missing TTL, Map-only attributes, payload columns) are the highest-recovery-cost pitfalls — they require table rewrites. Schema and convention must be designed together; replay's viability is decided here (payload column decision).
**Delivers:** Versioned migrations (wide `spans` table with promoted agent columns, `trace_id_ts` + `agent_runs` MVs, TTL, bloom filter, payload columns); batching write pipeline (Channel + flush by count/time); repository with Testcontainers integration tests.
**Uses:** client-v2 0.9.8 RowBinary batch inserts, ClickHouse 26.3 LTS, Testcontainers.
**Avoids:** Pitfalls 1, 2, 3, 6, 9 — all "fix later = table rewrite" class.

### Phase 3: OTLP Receivers (HTTP first, then gRPC)
**Rationale:** HTTP/protobuf is ~50 lines of Ktor route over the same protobuf parser — fastest end-to-end proof of the whole pipeline. gRPC adds the embedded grpc-netty-shaded server second. Both feed one ingest path.
**Delivers:** Spec-conformant `POST /v1/traces` (gzip, protobuf responses) and `TraceService/Export` on :4317 (raised message limits, partial_success, backpressure status codes); verified with real OTel SDK exporter + telemetrygen as senders.
**Uses:** opentelemetry-proto pre-generated stubs (no codegen pipeline), grpc-netty-shaded + grpc-bom.
**Avoids:** Pitfalls 4 and 8.

### Phase 4: Query API + DAG Assembler + Demo Emitter
**Rationale:** Completes the backend milestone — Docker Compose ingesting from an OTel SDK and answering every API query, fully testable with curl before React exists. Server-side assembly keeps DAG correctness inside Kotlin test coverage.
**Delivers:** Run-list endpoint (served entirely from `agent_runs` MV), trace endpoint with nested/classified/cost-rolled-up DAG JSON, saturation timeline series, replay step data, filters; orphan/incomplete-trace tolerance; demo emitter / sample-data generator (makes every feature demonstrable and exercises the convention as a real client).
**Avoids:** Pitfall 5 (query-time assembly, shuffled/late/orphaned span tests), anti-pattern 4 (DAG in untested JS).

### Phase 5: Frontend — Run List → DAG → Timeline → Replay
**Rationale:** Strictly after the backend per ecosystem rule; ordered so each screen consumes an already-proven endpoint, with the DAG (hardest UI) getting the most room.
**Delivers:** Vite/React scaffold; run list + debounced filters; elkjs-in-Web-Worker + D3 DAG with cost overlay, error coloring, collapsed-by-default subagent groups, span detail panel on node click; saturation timeline; minimal replay stepper with lazy-loaded context payloads. URL-driven routing from day one (free permalinks).
**Uses:** React 19, D3 7.9, elkjs 0.11, Zustand 5, TanStack Query 5.
**Avoids:** Pitfall 7 (5,000-span generated trace as the acceptance test), React/D3 DOM ownership conflict.

### Phase 6: Packaging + Distribution
**Rationale:** The Compose all-in-one is the flagship distribution promise and the highest-visibility failure surface for new users; backend retry/migration behavior was baked in earlier, this phase proves it cold.
**Delivers:** Compose with healthchecks, named volumes, ulimits, low-memory ClickHouse config, env-var credentials; SPA served from Ktor; CI compose smoke test (cold start on memory-limited runner, telemetrygen ingest, API query); README + convention doc polish.
**Avoids:** Pitfall 10.

### Phase Ordering Rationale

- **Convention before everything:** FEATURES.md dependency graph shows the attribute convention is required by the extractor, which is required by the DAG, cost overlay, saturation timeline, and filters — it must be artifact #1.
- **Schema before receivers:** the receivers' error-handling contract depends on the batching pipeline, and the batching pipeline writes to a schema whose mistakes are unrecoverable without rewrites. Storage decisions (payload columns) also gate replay.
- **HTTP receiver before gRPC:** same protobuf messages, one Ktor route, full pipeline proof in days instead of weeks; gRPC is additive.
- **Backend milestone before any React:** ecosystem rule, and server-side DAG assembly makes it genuinely possible — the hardest domain logic ships fully tested in Phase 4.
- **Pitfall alignment:** every critical pitfall maps to the phase where it is structurally prevented (1–3, 6, 9 → Phase 2; 4, 8 → Phase 3; 5 → Phase 4; 7 → Phase 5; 10 → Phase 6).

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 2 (schema):** the OTel Collector ClickHouse exporter schema was referenced but not fetched in depth (flagged MEDIUM in STACK.md) — pull the actual DDL and adapt it during phase planning; also decide payload column vs separate `span_payloads` table.
- **Phase 4 (replay/assembly):** PITFALLS.md explicitly flags replay-mode design (late/orphaned span handling, step ordering, clock skew) for deeper research when designing the assembler.
- **Phase 5 (DAG rendering):** elkjs compound-layout specifics in a Web Worker, node-count budgets, canvas fallback threshold — D3/elkjs items were MEDIUM-confidence community-verified; validate with a spike early in the phase.

Phases with standard patterns (skip research-phase):
- **Phase 1:** pure Kotlin domain modeling against a published OTel spec — no unknowns.
- **Phase 3:** OTLP receiver requirements are fully specified in the OTLP spec (HIGH confidence); the grpc-alongside-Ktor pattern is verified.
- **Phase 6:** Docker Compose hardening is a known checklist (already enumerated in PITFALLS.md).

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | All versions verified against Maven Central/npm on 2026-06-12, including jar-level verification of pre-generated gRPC stubs; compatibility matrix checked |
| Features | MEDIUM-HIGH | Official docs for Langfuse/LangSmith/Phoenix/OTel = HIGH; market-positioning and AgentOps replay claims from vendor blogs = MEDIUM; saturation-gap claim verified by checking each competitor's feature docs |
| Architecture | HIGH | Schema and pipeline patterns verified against SigNoz docs, ClickHouse engineering blog, Jaeger v2 design posts, OTel semconv spec; Kotlin gRPC specifics MEDIUM |
| Pitfalls | HIGH | ClickHouse + OTLP pitfalls from official docs/spec; D3 and Ktor/gRPC items MEDIUM (community-verified, consistent across sources) |

**Overall confidence:** HIGH

### Gaps to Address

- **gRPC implementation detail discrepancy:** ARCHITECTURE.md suggests grpc-kotlin; STACK.md's jar-verified finding (pre-generated Java stubs in opentelemetry-proto, ~10-line coroutine bridge, no codegen) supersedes it. **Resolution: follow STACK.md** — adopt grpc-kotlin only if the gRPC surface ever grows.
- **OTel Collector ClickHouse exporter schema:** referenced as the schema starting point but not fetched in depth — fetch the actual DDL during Phase 2 planning.
- **gen_ai semconv is status "Development":** attribute names have churned before (`gen_ai.system` → `gen_ai.provider.name`). Pin the targeted semconv version in the convention doc; all key strings live in `convention/` so renames are one-file changes.
- **Saturation timeline is emitter-gated:** zero value until something emits `context_saturation_pct`. The Phase 4 demo emitter is the mitigation; KORE adoption of the convention is the real unlock (KORE's work, not this project's).
- **kotlinx-rpc gRPC maturity:** monitored but excluded for v0.0.1; revisit only when it leaves development preview.

## Sources

### Primary (HIGH confidence)
- repo1.maven.org metadata + POM/jar inspection (Ktor, grpc, opentelemetry-proto, clickhouse-java, Kotlin, coroutines, Kotest, JUnit, protobuf, Testcontainers) — versions and stub verification
- registry.npmjs.org (react, d3, elkjs, zustand, vite, vitest, typescript, @tanstack/react-query) — versions and peer deps
- ClickHouse official: Java client docs, observability schema design, traces/spans blog, too-many-parts, async inserts, ClickStack materialized attributes
- OTLP Specification 1.10.0 — partial success, backpressure, gzip requirements
- OTel GenAI semantic conventions (agent spans, GenAI spans, 2026 observability blog)
- SigNoz architecture docs; Jaeger v2 ClickHouse design posts; otel-collector-contrib ClickHouse exporter README
- Langfuse / LangSmith / Phoenix / AgentOps official docs — competitor feature analysis
- grpc-ecosystem troubleshooting — grpc-netty-shaded recommendation

### Secondary (MEDIUM confidence)
- kotlinx-rpc releases / Ktor roadmap — gRPC-in-Ktor still preview
- elastic/apm-server #14759, otel-collector #10025 — 4 MB gRPC limit in practice
- Observe/Honeycomb posts — ingest-time assembly and out-of-order span failure modes
- SigNoz/Last9 Jaeger-vs-Tempo comparisons; Helicone/Braintrust vendor guides (cross-checked)
- Galileo/Sentry/Maxim guides — context-saturation practice recognition
- d3 #1936 + AntStack — D3 large-graph performance

### Tertiary (LOW confidence)
- OneUptime ClickHouse Compose post — memory settings cross-checked against ClickHouse docs

---
*Research completed: 2026-06-12*
*Ready for roadmap: yes*
