// Pure-JVM library module (no Paper visibility at all): content, behavior,
// pack, delivery, web, importers, integrations, testing, harness.
// Enforces master §10: these modules compile against api (+core) only.
//
// Catalog access goes through VersionCatalogsExtension (resolved in the
// consuming project) — type-safe `libs` accessors are not generated for
// precompiled script plugins that only apply other convention plugins.

plugins {
    id("materione.java-conventions")
    `java-library`
}

val libs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findBundle("materione-test").get())
    "testImplementation"(testFixtures(project(":testing")))
}
