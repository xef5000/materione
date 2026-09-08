plugins {
    id("materione.plugin-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
    api(project(":platform"))
    // The 26.x registry seam (D10 exception): the bootstrap and the
    // painting/song registrars live here so the floor-compiled module never
    // references post-floor APIs. Shaded like every other module.
    api(project(":modern"))
    api(project(":content"))
    api(project(":behavior"))
    api(project(":pack"))
    api(project(":delivery"))
    // The editor transport facade (phase 10). web/ shades Undertow via this
    // edge; R8 keeps every protocol reference inside dev.materione.web.
    api(project(":web"))
}

// The editor SPA builds with Node (:webui) and rides inside the shaded jar
// under webui/ — the direct-mode listener serves it from classpath resources.
val spaDist = rootProject.project(":webui").layout.buildDirectory.dir("spa")
tasks.named<ProcessResources>("processResources") {
    dependsOn(":webui:assembleSpa")
    from(spaDist) {
        into("webui")
    }
}
