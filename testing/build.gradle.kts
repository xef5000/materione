plugins {
    id("materione.java-conventions")
    `java-test-fixtures`
}

dependencies {
    // ArchUnit rules live in testFixtures; golden-file helper is dependency-free.
    testFixturesImplementation(libs.archunit.junit5)
    // FakeMailbox (the relay stand-in) parses JSON shapes; gson only, no product deps.
    testFixturesImplementation(libs.gson)
    "testImplementation"(libs.bundles.materione.test)
}
