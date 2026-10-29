// The editor transport: depends ONLY on :api (R9, WEB_ONLY_CONSUMES_API) —
// every action it serves is a typed MaterioneAPI operation; no Paper types
// visible (master §10).
plugins {
    id("materione.library-conventions")
}

dependencies {
    api(project(":api"))
    // Wire-protocol JSON encoding. Server-provided at runtime (Paper ships
    // Gson) — compileOnly like SnakeYAML/Gson everywhere else (build.md §1);
    // the shaded artifact must not pick it up (plugin-conventions relocates
    // only creative's own Gson copy).
    compileOnly(libs.gson)
    testImplementation(libs.gson)
    // Embedded editor listener (web.md §4.4). Shaded + relocated by
    // plugin-conventions; R8 keeps every reference inside this module.
    implementation(libs.undertow.core)
    // Test fixtures build the real api implementations (core-owned).
    testImplementation(project(":core"))
    // The GUI ops' golden frames come from the real GUI surface (content-owned). Test-only: the
    // production classes of web still depend on api alone (R9).
    testImplementation(project(":content"))
}
