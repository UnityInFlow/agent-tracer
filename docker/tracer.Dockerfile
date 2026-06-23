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
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /src

# Copy the whole repo (Gradle needs settings + all modules to resolve :backend).
COPY . .

# Produce build/install/backend/{bin,lib}. --no-daemon keeps the build hermetic.
RUN chmod +x ./gradlew && ./gradlew :backend:installDist --no-daemon

# ---- Stage 2: minimal JRE runtime ----------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# The application-plugin distribution: launcher at /app/bin/backend + /app/lib/*.jar.
COPY --from=builder /src/backend/build/install/backend/ /app/

# curl/wget-free liveness probe (bash /dev/tcp) — eclipse-temurin:21-jre has neither (Pitfall 4).
COPY docker/healthcheck.sh /app/healthcheck.sh
RUN chmod +x /app/healthcheck.sh

# OTLP/gRPC :4317, OTLP/HTTP :4318, JSON query API + /healthz :8080.
EXPOSE 4317 4318 8080

# Container-level healthcheck (Compose also declares one; this makes the image self-describing).
HEALTHCHECK --interval=5s --timeout=3s --retries=20 --start-period=20s \
  CMD ["/app/healthcheck.sh"]

CMD ["/app/bin/backend"]
