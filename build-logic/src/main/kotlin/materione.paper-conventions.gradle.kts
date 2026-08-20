// Paper-visible library module: api, core, platform, plugin.
// paper-api + snakeyaml are compileOnly/provided — they must never leak onto
// a runtime classpath or into a shaded jar (the server provides both).

plugins {
    id("materione.java-conventions")
    `java-library`
}

val libs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    compileOnly(libs.findLibrary("paper-api").get())
    compileOnly(libs.findLibrary("snakeyaml").get())
    // Test runtime needs snakeyaml (core's SettingsService parses real YAML)
    // — still never shipped: compileOnly + testImplementation, no implementation.
    "testImplementation"(libs.findLibrary("snakeyaml").get())
    "testImplementation"(libs.findBundle("materione-test").get())
    "testImplementation"(testFixtures(project(":testing")))
}
