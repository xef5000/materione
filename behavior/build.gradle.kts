// Pure-JVM content module: compiles against api+core only, no Paper types
// visible (master §10). Code lands with its milestone (see docs/roadmap.md).
plugins {
    id("materione.library-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
}
