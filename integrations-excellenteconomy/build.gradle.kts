// The ExcellentEconomy currency adapter (logic.md section 18), in its own module because its API is the one
// optional-plugin API compiled for a newer Java than the 21 floor (D10): ExcellentEconomy 2.8.0 is a Java 25
// jar, which a JDK 21 compiler refuses to read. Same exception as :modern: this module compiles on a JDK 25
// toolchain, and the conventions' --release 21 still governs the emitted bytecode, so the class files load on
// the 21 floor. They only ever run where ExcellentEconomy runs (a Java 25 server), because the integration is
// registered only while that plugin is enabled (integrations' Integrations gate); everywhere else the class is
// never loaded. Depends on api only among Materione modules; :integrations wires it in.
plugins {
    id("materione.paper-conventions")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(project(":api"))
    // The server's ExcellentEconomy supplies these classes at runtime; nothing here is shaded.
    compileOnly(libs.excellenteconomy.api)
    testImplementation(libs.excellenteconomy.api)
    // ExcellentCurrency extends a nightcore interface, so the tests need it to load the type. Test-only.
    testImplementation(libs.nightcore.api)
}
