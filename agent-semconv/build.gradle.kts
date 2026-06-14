plugins {
    alias(libs.plugins.kotlin.jvm)
    id("agenttracer.publishing")
}

dependencies {
    // CRITICAL (D-03): agent-semconv must have zero/near-zero transitive deps —
    // only the Kotlin stdlib at runtime. It is the artifact KORE depends on as
    // an emitter, so it must NOT pull the OTLP proto types, ClickHouse, or any
    // trace backend. Test dependencies only below.
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

publishing {
    publications {
        named<MavenPublication>("maven") {
            pom {
                name.set("agent-tracer — Agent Semconv")
                description.set(
                    "Vendored gen_ai.* and unityinflow.* attribute key constants " +
                        "plus a typed GenAiOperation enum — the machine-readable " +
                        "surface of the agent-tracer span convention (CONV-01).",
                )
            }
        }
    }
}
