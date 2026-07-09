# agent-tracer — Compose demo-emitter image (one-shot seeder, SC5).
#
# Multi-stage mirror of tracer.Dockerfile: a JDK builder runs
# `:demo-emitter:installDist` (Plan 03 application plugin), then an
# eclipse-temurin:21-jre runtime runs the launcher once and exits.
#
# Build context is the REPO ROOT (compose sets `context: ..`). No secret is baked
# in — the OTLP_ENDPOINT target is supplied via Compose runtime env.

# ---- Stage 1: build the installDist distribution -------------------------------
# Builder pinned to the BUILD host platform: Gradle runs natively ONCE — the
# runtime stage is COPY-only, so buildx assembles the foreign-arch variant with
# zero QEMU (D-06).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS builder
WORKDIR /src

COPY . .

# Produce build/install/demo-emitter/{bin,lib}.
RUN chmod +x ./gradlew && ./gradlew :demo-emitter:installDist --no-daemon

# ---- Stage 2: minimal JRE runtime ----------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Links the GHCR package to this repo (RESEARCH Pitfall 3).
LABEL org.opencontainers.image.source="https://github.com/UnityInFlow/agent-tracer"

COPY --from=builder /src/demo-emitter/build/install/demo-emitter/ /app/

# One-shot: emit N seeded runs over OTLP/HTTP to the tracer, flush, exit (restart: "no" in compose).
CMD ["/app/bin/demo-emitter"]
