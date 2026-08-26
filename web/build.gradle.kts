// Pure-JVM content module: compiles against api+core only, no Paper types
// visible (master §10). Code lands with its milestone (see docs/roadmap.md).
plugins {
    id("materione.library-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
    // Wire-protocol JSON encoding. Server-provided at runtime (Paper ships
    // Gson) — compileOnly like SnakeYAML/Gson everywhere else (build.md §1);
    // the shaded artifact must not pick it up (plugin-conventions relocates
    // only creative's own Gson copy).
    compileOnly(libs.gson)
    testImplementation(libs.gson)
    // Embedded editor listener (web.md §4.4). Shaded + relocated by
    // plugin-conventions; R8 keeps every reference inside this module.
    implementation(libs.undertow.core)
}
