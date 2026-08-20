plugins {
    id("materione.java-conventions")
    `java-test-fixtures`
}

dependencies {
    // ArchUnit rules live in testFixtures; golden-file helper is dependency-free.
    testFixturesImplementation(libs.archunit.junit5)
    "testImplementation"(libs.bundles.materione.test)
}
