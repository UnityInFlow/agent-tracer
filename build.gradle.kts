plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinter) apply false
    // Sonatype Central Portal aggregation (D-03). The nmcp plugin marker is
    // already on the build classpath via buildSrc's dependency on
    // `com.gradleup.nmcp:nmcp:1.4.4` (applied in the agenttracer.publishing
    // convention plugin), so we apply the aggregation sibling plugin WITHOUT a
    // version to avoid a "plugin already on classpath with unknown version"
    // resolution conflict.
    id("com.gradleup.nmcp.aggregation")
}

repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "org.jmailen.kotlinter")

    group = "io.github.unityinflow"
    version = "0.0.1-SNAPSHOT" // Pre-v0.0.1; default workflow is publishToMavenLocal until release.

    repositories {
        mavenCentral()
    }
}

// ── Sonatype Central Portal aggregation (D-03) ─────────────────────────────
//
// nmcp's aggregation plugin collects the `maven` publication from each
// subproject listed below and bundles them for a SINGLE upload to the
// Sonatype Central Portal. `publishingType = "USER_MANAGED"` means the staging
// bundle is NOT auto-released — it must be manually released via the portal UI
// after inspection.
//
// Only agent-semconv is aggregated: it is the KORE-facing contract artifact.
// The backend extractor stays internal in v0.0.1 (D-03) and is not published.
// The aggregation block exists but is not exercised until release.

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("SONATYPE_USERNAME")
        password = providers.environmentVariable("SONATYPE_PASSWORD")
        publishingType = "USER_MANAGED"
        publicationName = "agent-tracer-${project.version}"
    }
}

dependencies {
    // The single publishable module — the KORE-facing contract surface (D-03).
    nmcpAggregation(project(":agent-semconv"))
}
