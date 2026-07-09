# agent-tracer — Compose tracer image (DIST-01 / SC5).
#
# Multi-stage: a JDK builder runs `:backend:installDist` (the Gradle application
# plugin distribution added in Plan 01), then a slim eclipse-temurin:21-jre
# runtime copies just the distribution + a curl/wget-free healthcheck.
#
# Build context is the REPO ROOT (compose sets `context: ..`), so paths below are
# repo-relative. No CLICKHOUSE_* secret is baked in — credentials arrive only via
# Compose runtime env (mirrors ClickHouseConfig.fromEnv; threat T-04-16).

# ---- Stage 1: build the installDist distribution -------------------------------
# Builder pinned to the BUILD host platform: Gradle runs natively ONCE — the
# runtime stage below is COPY-only, so buildx assembles the foreign-arch variant
# with zero QEMU (D-06, RESEARCH Pattern 2). The artifact is arch-independent
# JVM bytecode.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS builder
WORKDIR /src

# Copy the whole repo (Gradle needs settings + all modules to resolve :backend).
COPY . .

# Produce build/install/backend/{bin,lib}. --no-daemon keeps the build hermetic.
RUN chmod +x ./gradlew && ./gradlew :backend:installDist --no-daemon

# ---- Stage 2: minimal JRE runtime ----------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Links the GHCR package to this repo (RESEARCH Pitfall 3).
LABEL org.opencontainers.image.source="https://github.com/UnityInFlow/agent-tracer"

# The application-plugin distribution: launcher at /app/bin/backend + /app/lib/*.jar.
COPY --from=builder /src/backend/build/install/backend/ /app/

# curl/wget-free liveness probe (bash /dev/tcp) — eclipse-temurin:21-jre has neither (Pitfall 4).
# COPY --chmod replaces the former RUN chmod: the runtime stage must stay
# COPY-only so the amd64 leg builds without QEMU (D-06).
COPY --chmod=0755 docker/healthcheck.sh /app/healthcheck.sh

# OTLP/gRPC :4317, OTLP/HTTP :4318, JSON query API + /healthz :8080.
EXPOSE 4317 4318 8080

# Container-level healthcheck (Compose also declares one; this makes the image self-describing).
HEALTHCHECK --interval=5s --timeout=3s --retries=20 --start-period=20s \
  CMD ["/app/healthcheck.sh"]

CMD ["/app/bin/backend"]
