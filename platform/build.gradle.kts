// Platform module: the only version-aware code (D10). Depends on api (the
// contracts) and core (the runtime model it applies shadows onto) — master
// §10 forbids api depending on internals and the leaf rules; platform→core
// is the composition the apply stage needs (as-built M1).
plugins {
    id("materione.paper-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
}
