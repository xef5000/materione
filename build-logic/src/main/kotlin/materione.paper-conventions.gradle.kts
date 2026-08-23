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
    // Test runtimes need snakeyaml (core's SettingsService parses real YAML)
    // and paper-api (platform tests resolve Bukkit types) — still never
    // shipped: compileOnly + testImplementation, no implementation.
    "testImplementation"(libs.findLibrary("snakeyaml").get())
    "testImplementation"(libs.findLibrary("paper-api").get())
    "testImplementation"(libs.findBundle("materione-test").get())
    "testImplementation"(testFixtures(project(":testing")))
}
