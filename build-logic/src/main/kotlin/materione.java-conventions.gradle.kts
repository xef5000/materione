// Baseline conventions shared by every Materione JVM module (product and infra).
// See docs/architecture/build.md.

plugins {
    java
}

group = "dev.materione"
base { archivesName = "materione-${project.name}" }

java {
    toolchain {
        // No vendor constraint: locally-resolvable JDKs (e.g. ~/.jdks) win;
        // foojay provisions a JDK elsewhere (CI, fresh machines).
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial,-classfile"))
    if (!providers.gradleProperty("materione.noWerror").isPresent) {
        options.compilerArgs.add("-Werror")
    }
}

// Reproducible archives (D7): no timestamps, stable entry order.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// JUnit platform launcher: required on the test runtime classpath for the
// platform to load at all (Gradle no longer injects it implicitly).
val materioneLibs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
dependencies {
    "testRuntimeOnly"(materioneLibs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    if (name == "integrationTest") {
        // :harness:integrationTest exists to run exactly these. Do NOT add the
        // default exclusion: a tag both included and excluded is silently
        // excluded by JUnit (Gradle only warns — the run would be a false green).
        useJUnitPlatform()
    } else {
        useJUnitPlatform {
            // Integration tests (@Tag("integration")) run via :harness:integrationTest only.
            excludeTags("integration")
        }
    }
    // Golden-file fixture corpus location (fixtures/ is not a Gradle module).
    systemProperty(
        "materione.fixtures.root",
        rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath,
    )
    // The fixtures are test INPUTS by path (not by dependency): without this,
    // a corpus change leaves test tasks falsely up-to-date / cache-hittable.
    inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Golden regeneration opt-in (-Dmaterione.golden.regen=true): review the
    // diff, then commit; CI never passes it (build.md §6).
    systemProperty(
        "materione.golden.regen",
        System.getProperty("materione.golden.regen") ?: "false",
    )
}
