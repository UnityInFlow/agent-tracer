plugins {
    alias(libs.plugins.kotlin.jvm)
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

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.clickhouse)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
