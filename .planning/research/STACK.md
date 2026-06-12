# Stack Research

**Domain:** OTLP trace receiver + ClickHouse trace store + React DAG visualizer (agent-tracer)
**Researched:** 2026-06-12
**Confidence:** HIGH (all versions verified against Maven Central / npm registry on research date; architectural choices cross-checked against official docs)

> Hard constraints honored: Kotlin 2.0+ (JVM 21) + Ktor backend, React 19 + D3.js frontend, ClickHouse storage, OTLP gRPC + HTTP/protobuf, Docker Compose distribution. This document picks the specific libraries *within* those constraints.

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
| spec-ci-plugin (Tool 04) | Lint CLAUDE.md in CI | Ecosystem requirement; `runs-on: [arc-runner-unityinflow]`. |

## Key Architecture-Affecting Decisions

### 1. gRPC receiver: pre-generated Java stubs + coroutine bridge (no protoc pipeline)

`io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha` already contains `TraceServiceGrpc.TraceServiceImplBase`. Implement it directly and bridge into coroutines with a small adapter (launch the ingest pipeline in a `CoroutineScope`, complete the `StreamObserver` on finish). The OTLP Export call is unary — the bridge is ~10 lines.

**Why not grpc-kotlin suspend stubs?** They require regenerating stubs from vendored `.proto` files via protobuf-gradle-plugin (0.10.0) + grpc-kotlin (1.5.0) protoc plugins — a whole codegen pipeline for one unary RPC. Adopt it later only if the gRPC surface grows. (grpc-kotlin 1.5.0 is current if you do.)

### 2. OTLP/HTTP is a plain Ktor route

`POST /v1/traces` with `Content-Type: application/x-protobuf` → `ExportTraceServiceRequest.parseFrom(bytes)`. Same message types as the gRPC path, so both transports feed one ingest pipeline. Enable Ktor's `Compression` plugin for gzip request bodies (OTel SDKs send gzip by default). OTLP/JSON can be deferred — HTTP/protobuf + gRPC covers every OTel SDK.

### 3. ClickHouse access: client-v2, not JDBC

Official guidance: client-v2 for new apps; the JDBC driver is a compatibility layer on top of it. client-v2 gives RowBinary streaming inserts (the throughput-critical path for span batches) and an async API without JDBC's blocking ceremony. Wrap calls in `Dispatchers.IO` or use the `CompletableFuture` API with `.await()`. Schema reference: the OTel Collector ClickHouse exporter schema (opentelemetry-collector-contrib) is the proven starting point for span tables — adapt it, adding materialized columns for the agent attributes (`skill_name`, `context_saturation_pct`, `tokens_used`, `cost_usd`).

### 4. DAG layout: elkjs computes positions, D3 renders

Pipeline: spans → graph model → elkjs layout (in Web Worker) → D3 renders SVG nodes/edges, `d3-zoom` for pan/zoom, D3 scales for cost-overlay color encoding. This honors the D3 constraint while outsourcing the NP-hard layout problem. elkjs is the only maintained JS layout engine with first-class compound nodes (nested subagent DAGs).

## Installation

```kotlin
// gradle/libs.versions.toml (excerpt) — backend
kotlin = "2.4.0"
ktor = "3.5.0"
grpc = "1.82.0"
otelProto = "1.10.0-alpha"
protobuf = "4.35.1"
clickhouse = "0.9.8"
coroutines = "1.11.0"
junit = "5.14.4"
kotest = "6.1.11"
testcontainers = "1.21.4"
```

```bash
# Frontend
npm install react@19 react-dom@19 d3@7 elkjs@0.11 zustand@5 @tanstack/react-query@5
npm install -D vite@8 @vitejs/plugin-react@6 typescript@6 vitest@4 @testing-library/react@16 happy-dom@20
```

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

**If span ingest volume grows beyond toy scale (>1k spans/sec):**
- Buffer spans in a `Channel`/`Flow` and batch-insert via client-v2 RowBinary every N spans or T milliseconds — never row-at-a-time inserts (ClickHouse hates small inserts).
- Consider ClickHouse async_insert as a server-side fallback, but client-side batching is the primary mechanism.

**If layout becomes the frontend bottleneck (runs with 500+ spans):**
- elkjs in a Web Worker is already the plan; additionally collapse subagent groups by default and lay out lazily on expand.

**If the gRPC and HTTP receivers need shared auth later:**
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

---
*Stack research for: agent-tracer (OTLP receiver + ClickHouse + React/D3 DAG visualizer)*
*Researched: 2026-06-12*
