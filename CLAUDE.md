# agent-tracer — Purpose-Built Agent Call Graph Visualizer

## Project Overview

**Tool 09** in the [UnityInFlow](https://github.com/UnityInFlow) ecosystem.

Agent-aware trace visualizer that understands plan steps, skill activations, context saturation, and subagent spawns. Renders interactive DAGs with token cost overlays and context window saturation timelines. Replaces Jaeger/Zipkin for agent debugging with agent-native semantics.

**Phase:** 3 | **Stack:** Kotlin (Ktor) + React + D3.js | **Distribution:** Docker Compose + Helm chart

## Status

Planning in progress (GSD initialized June 2026). `token-dashboard` (Tool 06) shipped v0.1.0 — metrics patterns available. `agent-memory` (Tool 10) still planned — context data integration deferred until it exists; do not block planning on it.

## Reference Documents

- `09-agent-tracer.md` — Feature spec, key features checklist, agent DAG view, ClickHouse storage, implementation todos (Month 8)
- `claude-code-harness-engineering-guide-v2.md` — Harness engineering patterns and best practices

Read these before making architectural or scope decisions.

## Tooling

| Tool | Status | Usage |
|---|---|---|
| **GSD** | Installed (global) | `/gsd:new-project` to scaffold when ready. `/gsd:plan-phase` and `/gsd:execute-phase` for structured development. |
| **RTK** | Active (v0.34.2) | Automatic via hooks. Compresses gradle, git output. ~80% token savings. |
| **Superpowers** | Active (v5.0.5) | Auto-triggers brainstorming, TDD, planning, code review, debugging skills. |

## Constraints

### Kotlin — Backend (inherited from ecosystem CLAUDE.md)

- Kotlin 2.0+, JVM target 21
- Gradle (Kotlin DSL only — never Groovy)
- Test with JUnit 5 + Kotest matchers
- Coroutines for all async work — never `Thread.sleep()` or raw threads
- Immutable data classes preferred over mutable state
- Sealed classes for domain modelling
- No `var` — always `val`
- No `!!` without a comment explaining why it's safe
- `ktlint` before every commit
- Group: `io.github.unityinflow` (ecosystem-wide rename from `dev.unityinflow`, May 2026)

### Frontend

- React 19 + D3.js for DAG visualization
- Zustand or similar for state management

### General

- Test coverage >80% on core logic before release
- No secrets committed — all credentials via environment variables

## Acceptance Criteria

Target release: v0.0.1

- [ ] OTLP receiver: ingests traces from KORE and any OTel-compatible source
- [ ] Agent DAG view: full decision tree of any agent run as interactive graph
- [ ] Agent-aware span attributes: skill_name, context_saturation_pct, tokens_used, cost_usd
- [ ] Token cost overlaid on every node in the DAG
- [ ] Context window saturation timeline
- [ ] Subagent hierarchy: parent-child relationships as nested DAG
- [ ] Replay mode: step through agent decisions with context viewer
- [ ] Filters: by agent, model, cost range, outcome, date range
- [ ] ClickHouse storage for time-series trace data at scale
- [ ] Docker Compose all-in-one: tracer + ClickHouse

## Development Workflow

GSD initialized 2026-06-12: `.planning/` contains PROJECT.md, REQUIREMENTS.md (22 v1 requirements), ROADMAP.md (5 phases), and research/. Per phase:

1. `/gsd-discuss-phase N` — lock in open decisions
2. `/gsd-plan-phase N` — atomic task plans with file paths
3. `/gsd-execute-phase N` — execution with fresh context windows

Phases: 1 Attribute Convention → 2 ClickHouse Storage → 3 OTLP Ingestion → 4 Query API + Demo Emitter + Compose → 5 Frontend. Next up: `/gsd-discuss-phase 1`.

Superpowers skills (TDD, code review, debugging) activate automatically during execution.

## Key Dependencies (for reference, not installed yet)

Researched and version-verified — see the Technology Stack section below and `.planning/research/STACK.md`:

- `ktor-server-netty` — HTTP server (OTLP/HTTP :4318, query API, SPA)
- `io.opentelemetry.proto:opentelemetry-proto` — OTLP messages + pre-generated gRPC stubs (no protoc pipeline)
- `grpc-netty-shaded` — standalone gRPC server (:4317) alongside Ktor; Ktor cannot host gRPC
- `com.clickhouse:client-v2` — ClickHouse client (official recommendation over JDBC for new apps)
- `react` + `d3` + `elkjs` — frontend visualization (elkjs for compound-node DAG layout)

---

## CI / Runners

This repo is public. The org runner group does not allow public repositories
(`allows_public_repositories: false`), so all workflows run on GitHub-hosted runners (free for public repos):

- **Fork/public CI (`ci.yml`)**: secretless, `permissions: contents: read`.
- **Release work (`release.yml`)**: GHCR push + GitHub Release, triggered only by tag pushes forks cannot fire.

```yaml
runs-on: ubuntu-24.04
```

Use the fixed image label, not `ubuntu-latest`. The org requires every `uses:` to be pinned to a full
commit SHA with a `# vX.Y.Z` comment, and only allow-listed actions run. The former self-hosted labels
`arc-runner-unityinflow` and `orangepi` have no runner any more; do not use them.

CI must include the `spec-ci-plugin` GitHub Action (Tool 04) to lint this CLAUDE.md with spec-linter on every PR (decision: June 2026).

---

## Do Not

- Do not use `var` in Kotlin — always `val`
- Do not use `!!` without a comment explaining why it's safe
- Do not build the frontend until the backend API is working and tested
- Do not commit secrets or API keys
- Do not skip writing tests
- Do not inline the reference docs into this file — read them by path

<!-- GSD:project-start source:PROJECT.md -->

## Project

**agent-tracer**

A purpose-built agent call graph visualizer — Tool 09 of the UnityInFlow ecosystem. It ingests OpenTelemetry traces from KORE (and any OTel-compatible source), stores them in ClickHouse, and renders agent runs as interactive DAGs with token cost overlays, context window saturation timelines, and a replay mode. It replaces Jaeger/Zipkin for agent debugging with agent-native semantics: plan steps, skill activations, context saturation, subagent spawns.

**Core Value:** A developer debugging an agent failure can see the full decision tree of any agent run — with token cost and context saturation on every node — instead of reading thousands of generic spans.

### Constraints

- **Tech stack**: Kotlin 2.0+ (JVM 21) + Ktor backend, React 19 + D3.js frontend, ClickHouse storage, OTLP protocol — fixed by ecosystem spec
- **Build order**: Backend must be built and tested before any frontend work — ecosystem rule
- **Build tooling**: Gradle Kotlin DSL only; JUnit 5 + Kotest; coroutines for all async (no `Thread.sleep()`/raw threads); `ktlint` before every commit
- **Kotlin style**: no `var`, no `!!` without a safety comment, sealed classes for domain modelling, immutable data classes
- **Quality**: >80% test coverage on core logic before release
- **Security**: no secrets committed — credentials via environment variables only
- **CI**: GitHub-hosted `ubuntu-24.04` for CI and release (public repo); release only on tag push; actions SHA-pinned

<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->

## Technology Stack

## Recommended Stack

### Core Technologies — Backend

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Kotlin | 2.4.0 | Language | Latest stable (verified Maven Central). All kotlinx libs (coroutines/serialization 1.11.0) support it. Fallback to 2.3.21 only if ktlint parsing lags. |
| Ktor (server-netty) | 3.5.0 | HTTP server: OTLP/HTTP endpoint (4318), query REST API, serves SPA | Latest stable. Netty engine chosen for HTTP/2 maturity and battle-tested production use; grpc-netty-shaded is shaded so no classpath conflict. |
| grpc-netty-shaded + grpc-stub + grpc-protobuf | 1.82.0 | OTLP/gRPC receiver on port 4317 | **Ktor cannot host gRPC services** — this is the canonical JVM pattern: run an `io.grpc.Server` alongside Ktor in the same JVM, both tied to app lifecycle. Shaded Netty avoids version conflicts with Ktor's Netty. |
| io.opentelemetry.proto:opentelemetry-proto | 1.10.0-alpha | OTLP protobuf messages **and** pre-generated gRPC service stubs | **Key finding (verified by jar inspection):** this artifact ships `TraceServiceGrpc` with an `AsyncService`/`TraceServiceImplBase` you can implement directly. **No protoc/protobuf-gradle-plugin pipeline needed.** The `-alpha` suffix is a permanent publishing convention of opentelemetry-proto-java; the OTLP v1 trace proto itself is stable. |
| protobuf-java | 4.35.1 | Protobuf runtime | Must be ≥4.34.0 (opentelemetry-proto 1.10.0's declared dependency). 4.35.1 is latest; grpc-java 1.82 is on the protobuf 4.x line — compatible. |
| ClickHouse Java client (`com.clickhouse:client-v2`) | 0.9.8 | ClickHouse inserts + queries over HTTP protocol | Official ClickHouse recommendation for new applications (clickhouse.com/docs/integrations/java). Cleaner API than JDBC, RowBinary streaming inserts (critical for span batch ingest throughput), async API bridges to coroutines via `CompletableFuture.await()`. |
| ClickHouse server | 26.3 LTS (`clickhouse/clickhouse-server:26.3`) | Trace storage | Current LTS line (verified GitHub releases, 26.3.13 latest LTS patch). Pin the LTS tag in Docker Compose, not `latest`. |
| kotlinx-coroutines-core | 1.11.0 | All async work (ecosystem rule) | Latest stable. `kotlinx-coroutines-jdk8` (same version) provides `.await()` for client-v2 futures. |
| kotlinx-serialization-json | 1.11.0 | Query/UI REST API payloads | Latest stable; pairs with `ktor-server-content-negotiation` + `ktor-serialization-kotlinx-json` 3.5.0. |

### Core Technologies — Frontend

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| React | 19.2.7 | UI framework | Constraint; latest 19.x verified on npm. |
| D3.js | 7.9.0 | DAG rendering, zoom/pan (`d3-zoom`), scales, timeline view | Constraint; 7.9.0 is current (D3 v7 is the stable line — no v8 exists). |
| elkjs | 0.11.1 | DAG **layout** engine (ELK layered algorithm) | **The deciding factor: elkjs natively supports compound/nested nodes**, which is exactly the "subagent hierarchy as nested DAG" acceptance criterion. dagre and d3-dag have no real compound-node support. Run it in a Web Worker (~1.4 MB, layout is CPU-bound) — elkjs ships worker support out of the box. Actively maintained (published 2026-03). |
| Zustand | 5.0.14 | Client state (selected run, replay cursor, filters) | Constraint allows "Zustand or similar"; Zustand 5 is the simplest fit for replay-mode stepping state. |
| TanStack Query | 5.101.0 | Server state: trace/run fetching, filter queries, caching | De facto standard for REST data fetching in React 19 apps; keeps Zustand purely for UI state. |
| Vite | 8.0.16 | Build tool | Latest stable major (Rolldown-based). `@vitejs/plugin-react` 6.0.2 requires Vite ^8 — verified peer deps. |
| TypeScript | 6.0.3 | Language | Latest stable (published 2026-04, two months mature). |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| JUnit Jupiter | 5.14.4 | Test runner (backend) | Ecosystem constraint is JUnit 5 — use latest 5.x, **not** JUnit 6.x (6.1.0 exists but is out of constraint). |
| Kotest assertions (`kotest-assertions-core`) | 6.1.11 | Matchers | Kotest 6 is now stable (don't use 5.x or 6.0 milestones). Assertions-only; JUnit 5 remains the runner per ecosystem convention. |
| Testcontainers (`org.testcontainers:clickhouse`) | 1.21.4 | Integration tests against real ClickHouse | Schema + insert/query tests. ClickHouse SQL is too dialect-specific to mock meaningfully. |
| MockK | 1.14.11 | Mocking (backend) | Unit tests for the ingest pipeline. |
| kotlin-logging-jvm + logback-classic | 8.0.4 / 1.5.34 | Logging | Standard Kotlin logging facade over SLF4J. |
| Vitest | 4.1.8 | Frontend unit tests | Peer-compatible with Vite ^8 (verified). |
| @testing-library/react | 16.3.2 | Component tests | Supports React 19. |
| happy-dom | 20.10.2 | Test DOM environment | Faster than jsdom for Vitest; jsdom 29.1.1 if compatibility issues arise. |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Gradle (Kotlin DSL) | 9.5.1 | Current stable; use version catalog (`libs.versions.toml`). |
| ktlint (jlleitschuh Gradle plugin) | Format before every commit | Ecosystem rule. |
| Docker Compose | tracer + ClickHouse all-in-one | App image: `eclipse-temurin:21-jre`. Expose 4317 (gRPC), 4318 (HTTP), 8080 (UI/API). |
| spec-ci-plugin (Tool 04) | Lint CLAUDE.md in CI | Ecosystem requirement; runs in `ci.yml` on `ubuntu-24.04`. |

## Key Architecture-Affecting Decisions

### 1. gRPC receiver: pre-generated Java stubs + coroutine bridge (no protoc pipeline)

### 2. OTLP/HTTP is a plain Ktor route

### 3. ClickHouse access: client-v2, not JDBC

### 4. DAG layout: elkjs computes positions, D3 renders

## Installation

# Frontend

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| Pre-generated Java stubs (opentelemetry-proto) | grpc-kotlin 1.5.0 codegen from vendored protos | If you add many custom gRPC services beyond OTLP and want suspend-native handlers everywhere. |
| Standalone `io.grpc.Server` next to Ktor | kotlinx-rpc gRPC integration | Only when kotlinx-rpc's gRPC support leaves development preview (it is explicitly "APIs may break without notice" as of its latest gRPC pre-releases). Not for v0.0.1. |
| client-v2 | clickhouse-jdbc 0.9.8 (JDBC v2 driver) | If you need JDBC ecosystem tooling (connection pools, ORMs, Flyway-style migrators). Not needed here. |
| elkjs | d3-dag 1.2.1 | Lighter (pure TS, Sugiyama) and more D3-idiomatic — fine if the nested-subagent view were dropped; it has no compound-node layout. |
| elkjs + D3 SVG | @xyflow/react (React Flow) 12.11.0 | The 2025/2026 default for React node graphs (built-in pan/zoom/minimap/interaction) — but it owns rendering, conflicting with the D3.js ecosystem constraint. Revisit only if the constraint is relaxed. |
| Ktor Netty engine | Ktor CIO engine | CIO is pure-Kotlin and lighter; either works. Netty picked for HTTP/2 and production track record. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| kotlinx-rpc gRPC | Development preview; breaking-change disclaimer on every gRPC pre-release | grpc-netty-shaded 1.82.0 + pre-generated stubs |
| `io.opentelemetry:opentelemetry-proto` (old coordinates) | Deprecated/stale artifact line | `io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha` |
| `opentelemetry-sdk` as the receiver | The SDK *emits* telemetry; it has no receiver role. Listing it as a dependency (as the old CLAUDE.md sketch did) is a category error | opentelemetry-proto messages + your own gRPC/HTTP endpoints. (Use `opentelemetry-api`/SDK only if agent-tracer later self-instruments.) |
| `dagre` 0.8.5 (original package) | Unmaintained since 2019 | elkjs 0.11.1 (or `@dagrejs/dagre` 3.0.0 if you ever need dagre specifically — but it still lacks good compound layout) |
| `ru.yandex.clickhouse` / client-v1 API | Legacy, deprecated by ClickHouse Inc | `com.clickhouse:client-v2:0.9.8` |
| JUnit 6.x | Out of ecosystem constraint (JUnit 5 + Kotest) | junit-jupiter 5.14.4 |
| `clickhouse/clickhouse-server:latest` in Compose | Non-reproducible; ClickHouse moves fast | Pin `:26.3` LTS |

## Stack Patterns by Variant

- Buffer spans in a `Channel`/`Flow` and batch-insert via client-v2 RowBinary every N spans or T milliseconds — never row-at-a-time inserts (ClickHouse hates small inserts).
- Consider ClickHouse async_insert as a server-side fallback, but client-side batching is the primary mechanism.
- elkjs in a Web Worker is already the plan; additionally collapse subagent groups by default and lay out lazily on expand.
- gRPC `ServerInterceptor` + Ktor `Authentication` plugin against the same token validator — design the validator as a transport-agnostic class from day one.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| opentelemetry-proto 1.10.0-alpha | protobuf-java ≥4.34.0 | Verified in its POM; pin protobuf-java 4.35.1 explicitly |
| grpc-java 1.82.0 | protobuf-java 4.x | grpc-java is on the protobuf 4 line; no 3.x pinning anywhere |
| Vitest 4.1.8 | Vite ^6 \|\| ^7 \|\| ^8 | Verified peer range — Vite 8 OK |
| @vitejs/plugin-react 6.0.2 | Vite ^8 only | Verified peer range |
| @testing-library/react 16.3.2 | React 19 | Supported |
| Ktor 3.5.0 | Kotlin 2.4.0 | Kotlin stdlib is backward compatible; no issue |
| client-v2 0.9.8 | ClickHouse server 26.3 | HTTP-protocol client; server LTS fully supported |
| grpc-netty-shaded | Ktor Netty engine | Shaded — no Netty classpath conflict in one JVM |

## Sources

- repo1.maven.org maven-metadata.xml + POM/jar inspection (Ktor, grpc, opentelemetry-proto incl. jar-level verification of `TraceServiceGrpc` stubs, clickhouse-java, Kotlin, coroutines, Kotest, JUnit, protobuf, Testcontainers) — **HIGH** (authoritative registry, fetched 2026-06-12)
- registry.npmjs.org latest tags + publish dates + peer deps (react, d3, elkjs, d3-dag, @dagrejs/dagre, zustand, @xyflow/react, vite, vitest, @vitejs/plugin-react, typescript, @tanstack/react-query) — **HIGH** (authoritative registry, fetched 2026-06-12)
- [ClickHouse Java clients overview](https://clickhouse.com/docs/integrations/java) + [clickhouse-java GitHub](https://github.com/ClickHouse/clickhouse-java) — client-v2 recommended for new apps — **HIGH** (official docs)
- [kotlinx-rpc releases](https://github.com/Kotlin/kotlinx-rpc/releases) — gRPC support is development preview with breaking-change disclaimer — **MEDIUM** (official repo, summary fetched; exact current pre-release may be newer but status unchanged across sources)
- [Ktor docs](https://ktor.io/docs/tutorial-first-steps-with-kotlin-rpc.html) / [Ktor roadmap](https://blog.jetbrains.com/kotlin/2025/09/ktor-roadmap-2025/) — Ktor has no native gRPC hosting; kotlinx-rpc is the future path — **MEDIUM**
- ClickHouse GitHub releases (26.3 LTS line, 26.5 stable) — **HIGH**
- OTel Collector ClickHouse exporter schema (opentelemetry-collector-contrib) referenced as schema starting point — **MEDIUM** (pattern reference, not fetched in depth; flag for Phase 1 research)

<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->

## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->

## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->

## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:

- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->

## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
