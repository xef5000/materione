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
    // Soft-depend provider adapters (Vault, LuckPerms, the BungeeCord channel). Their classes are
    // loaded only when the matching plugin is enabled (integrations' Integrations gate); nothing
    // from them is shaded. logic.md §4.5.
    api(project(":integrations"))
    // The editor transport facade (phase 10). web/ shades Undertow via this
    // edge; R8 keeps every protocol reference inside dev.materione.web.
    api(project(":web"))
    // Anonymous usage metrics (bStats, MIT). Shaded + relocated under
    // materione.libs.org.bstats; MaterionePlugin is the only reference, behind
    // a fail-soft wrapper (plugin.md §7.2).
    implementation(libs.bstats.bukkit)
}

tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    addTestOutputListener { descriptor, event ->
        if (descriptor.className == "dev.materione.plugin.runtime.ContentPerfBudgetTest"
            && descriptor.name.startsWith("noOpReloadDecisionUnder100Milliseconds")
            && event.message.startsWith("MATERIONE_NOOP_PERF ")) {
            logger.lifecycle(event.message.trim())
        }
    }
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
