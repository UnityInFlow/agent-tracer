plugins {
    alias(libs.plugins.kotlin.jvm)
    // NOT publishing — the extractor stays internal in v0.0.1 (D-03).
}

dependencies {
    implementation(project(":agent-semconv"))
    // OTLP proto types (KeyValue/AnyValue) are the extractor's INPUT shape.
    // This dependency lives HERE, never in agent-semconv.
    implementation(libs.opentelemetry.proto)

    testImplementation(libs.junit5)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
