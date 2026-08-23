plugins {
    id("materione.plugin-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
    api(project(":platform"))
    api(project(":content"))
    api(project(":behavior"))
    api(project(":pack"))
    api(project(":delivery"))
}
