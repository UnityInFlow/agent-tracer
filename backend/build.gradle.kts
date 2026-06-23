plugins {
    alias(libs.plugins.kotlin.jvm)
    // @Serializable query/UI DTOs (Phase 4); compiler plugin only — ContentNegotiation
    // is installed on the new :8080 query server in Plan 04, not here.
    alias(libs.plugins.kotlin.serialization)
    // Runnable-jar packaging (installDist/distZip/run) the Compose tracer image (Plan 05) runs from.
    application
    // NOT publishing — the extractor stays internal in v0.0.1 (D-03).
}

dependencies {
    implementation(project(":agent-semconv"))
    // OTLP proto types (KeyValue/AnyValue) are the extractor's INPUT shape.
    // This dependency lives HERE, never in agent-semconv.
    implementation(libs.opentelemetry.proto)

    // ── Storage layer (Phase 2) ────────────────────────────────────────────
    // ClickHouse client-v2 for RowBinary batch inserts + queries.
    implementation(libs.clickhouse.client.v2)
    // All async work uses coroutines per CLAUDE.md; coroutines-core also
    // provides kotlinx.coroutines.future.await for the client-v2 future bridge.
    implementation(libs.coroutines.core)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)

    // ── OTLP receivers (Phase 3) ───────────────────────────────────────────
    // Ktor Netty hosts the OTLP/HTTP route; standalone io.grpc.Server hosts OTLP/gRPC.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    // protobuf-java as a DIRECT dependency so 4.35.1 wins over opentelemetry-proto's
    // transitive 4.34.0 (Pitfall 3 — a regression triggers NoSuchMethodError at runtime).
    implementation(libs.protobuf.java)

    // ── JSON query/UI API (Phase 4) ────────────────────────────────────────
    // @Serializable DTOs + Ktor JSON ContentNegotiation. On the classpath now;
    // ContentNegotiation { json() } is installed on the :8080 server in Plan 04.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    // runTest + virtual time for the SpanWriter flush/backpressure unit test (no real sleeps).
    testImplementation(libs.coroutines.test)
    testImplementation(libs.testcontainers.clickhouse)
    testImplementation(libs.testcontainers.junit)
    // Real OTel SDK exporters drive the end-to-end receiver integration tests.
    testImplementation(platform(libs.otel.bom))
    testImplementation(libs.otel.sdk)
    testImplementation(libs.otel.exporter.otlp)
    testImplementation(libs.ktor.server.test.host)
    // In-process gRPC channel for the OtlpGrpcService contract test (no socket, no Docker).
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    // Synthetic class for the file-level `fun main()` in app/TracerApplication.kt (file name + `Kt`).
    // Gives installDist/distZip/run for the Compose tracer image (Plan 05).
    mainClass.set("io.github.unityinflow.agenttracer.app.TracerApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
