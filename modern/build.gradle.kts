// The modern seam (D10 exception): registry-event registration for painting
// variants and jukebox songs. Those Paper APIs exist only on 1.21.6+, so this
// module compiles against a 26.x paper-api — everything here runs only after
// RegistryCapabilities confirms the API is present at runtime; a floor server
// (1.21.4/1.21.5) never links these classes and rides the world-datapack
// path. Compiles against api/core/content, plus platform for the one text
// pipeline (painting titles parse exactly like the datapack path, plugin
// §6.1), with no floor paper-api: the modern one replaces it via compileOnly
// below, and like every server-provided surface it is never shaded.
plugins {
    id("materione.java-conventions")
    `java-library`
}

// Paper 26.x (and its paper-api) requires a JVM 25 toolchain; the conventions'
// --release 21 still governs the emitted bytecode, so the shaded class files
// stay loadable on the 21 floor even though these classes only ever run on
// 26.x servers.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// The conventions' --release 21 pins the resolution attribute to JVM 21, but
// paper-api 26.x only publishes a JVM-25 variant — request that variant
// explicitly while the emitted bytecode stays release-21 (see toolchain note).
configurations.compileClasspath {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

val libs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    api(project(":api"))
    api(project(":core"))
    api(project(":content"))
    // MaterioneText only: Adventure-level code, no version-specific Bukkit types
    implementation(project(":platform"))
    compileOnly(libs.findLibrary("paper-api-modern").get())
    compileOnly(libs.findLibrary("snakeyaml").get())
    "testImplementation"(libs.findLibrary("snakeyaml").get())
    "testImplementation"(libs.findBundle("materione-test").get())
    "testImplementation"(testFixtures(project(":testing")))
}
