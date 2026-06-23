plugins {
    alias(libs.plugins.kotlin.jvm)
    // Runnable distribution (installDist) the Compose seeder image runs from.
    application
    // NOT publishing, NOT serialization — the emitter is an internal OTLP client
    // with no @Serializable types and no Maven Central surface.
}

dependencies {
    // The emitter sources EVERY convention attribute KEY from agent-semconv
    // constants (CONV-01) — never a hardcoded gen_ai.*/unityinflow.* literal.
    implementation(project(":agent-semconv"))

    // OTel SDK + OTLP/HTTP exporter: this module is an OTLP *client* of the
    // Phase-3 receiver, not the receiver. otel-bom aligns the SDK/exporter
    // versions; agent-tracer's own receiver lives in :backend.
    implementation(platform(libs.otel.bom))
    implementation(libs.otel.sdk)
    implementation(libs.otel.exporter.otlp)

    // Coroutines per CLAUDE.md (no raw threads/Thread.sleep anywhere).
    implementation(libs.coroutines.core)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    // InMemorySpanExporter captures emitted spans in-process for the SC4
    // determinism assertion (no live receiver needed).
    testImplementation(libs.otel.sdk.testing)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    // Synthetic class for the file-level `fun main()` in demo/DemoEmitter.kt
    // (file name + `Kt`). Gives installDist for the Compose seeder image.
    mainClass.set("io.github.unityinflow.agenttracer.demo.DemoEmitterKt")
}

tasks.test {
    useJUnitPlatform()
}
