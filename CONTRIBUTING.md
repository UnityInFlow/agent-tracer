# Contributing to agent-tracer

Thank you for your interest in contributing to the agent-tracer project!

## Prerequisites

- JDK 21+
- Gradle (wrapper included — use `./gradlew`)
- Docker Desktop (or another real Docker Engine) — required for the ClickHouse
  Testcontainers integration tests and the local compose stack

## Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/UnityInFlow/agent-tracer.git
   cd agent-tracer
   ```

2. Build the project (runs unit tests + ClickHouse Testcontainers ITs):
   ```bash
   ./gradlew build
   ```

3. Run the full local stack from source (dev build override — the committed
   `docker/compose.yaml` is pull-only and references released images):
   ```bash
   docker compose -f docker/compose.yaml -f docker/compose.build.yaml up --build
   ```
   The tracer is available at `http://localhost:8080` (OTLP/gRPC on `:4317`,
   OTLP/HTTP on `:4318`); the one-shot demo-emitter seeds example traces on
   cold start.

4. Run the cold-start smoke test against your from-source build:
   ```bash
   SMOKE_BUILD=1 ./scripts/compose-smoke.sh
   ```

## Coding Standards

- **Kotlin 2.0+** with JVM target 21
- **Immutable by default** — use `val`, never `var`
- **No `!!` assertions** — use Elvis operator (`?:`) or safe calls (`?.`),
  or add a comment explaining why it's safe
- **Sealed classes** for domain modelling (results, errors, states)
- **Coroutines** for all async work — never `Thread.sleep()` or raw threads
- Format with `ktlint` before every commit:
  ```bash
  ./gradlew ktlintFormat
  ```

## Testing

- Use **JUnit 5** with **Kotest matchers**
- Test coverage must be >80% on core logic
- ClickHouse behaviour is tested against a real server via **Testcontainers**
  (`org.testcontainers:clickhouse`) — never mocked SQL
- Name tests descriptively:
  ```kotlin
  @Test
  fun `ingests OTLP trace batch and serves it back via the runs query`() { ... }
  ```

## Pull Request Process

1. Create a feature branch from `main`:
   ```bash
   git checkout -b feat/your-feature
   ```
2. Make your changes with clear, atomic commits
3. Ensure all tests pass: `./gradlew test`
4. Ensure code is formatted: `./gradlew ktlintCheck`
5. Open a PR against `main` with a clear description

Fork PRs run on GitHub-hosted CI (secretless) — they never reach the org's
self-hosted runners.

## Commit Messages

Follow conventional commits:
```
feat: add span batch buffering to the ClickHouse writer
fix: correct run duration aggregation for open-ended spans
test: add edge cases for OTLP/HTTP payload validation
docs: update README with compose quickstart
chore: bump Ktor to 3.5.1
refactor: extract attribute mapping to agent-semconv helpers
```

## Architecture

- **Ktor (Netty)** — HTTP server: OTLP/HTTP receiver (`:4318`), JSON query API (`:8080`)
- **grpc-netty-shaded** — standalone OTLP/gRPC receiver (`:4317`) alongside Ktor
- **ClickHouse (client-v2)** — time-series trace storage
- **agent-semconv** — the typed agent span attribute convention
  (superset of OTel GenAI semconv + `unityinflow.*` extensions)
- **Docker Compose** — all-in-one stack: ClickHouse + tracer + demo-emitter

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
