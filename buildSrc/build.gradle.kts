plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Pull in the nmcp plugin marker so the convention plugin can apply
    // `id("com.gradleup.nmcp")`. Version pinned from gradle/libs.versions.toml
    // (nmcp = "1.4.4") — kept as a literal coordinate because convention-plugin
    // classpath dependencies cannot read version catalog plugin aliases
    // directly.
    implementation("com.gradleup.nmcp:nmcp:1.4.4")
}
