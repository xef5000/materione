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
    // the %materione_…% placeholder grammar (logic.md §3.3); the PlaceholderAPI class itself is in plugin
    api(project(":integrations"))
    // The editor transport facade (phase 10). web/ shades Undertow via this
    // edge; R8 keeps every protocol reference inside dev.materione.web.
    api(project(":web"))
    // Anonymous usage metrics (bStats, MIT). Shaded + relocated under
    // materione.libs.org.bstats; MaterionePlugin is the only reference, behind
    // a fail-soft wrapper (plugin.md §7.2).
    implementation(libs.bstats.bukkit)
    // PlaceholderAPI, compile-only: the %materione_*% expansion class (logic.md §3.3) is loaded only when
    // the server has PlaceholderAPI enabled. Not transitive: its own dependency tree is the server's.
    compileOnly(libs.placeholderapi) {
        isTransitive = false
    }
    testImplementation(libs.placeholderapi) {
        isTransitive = false
    }
}

// Explicit local-only addon for Folia ownership probes; never included in the production plugin jar.
val foliaProbe by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
tasks.register<Jar>("foliaProbeJar") {
    description = "Builds the local-only Folia read-safety sentinel addon."
    group = "verification"
    archiveClassifier.set("folia-probe")
    from(foliaProbe.output)
}

// Optional local-only PlaceholderAPI callback and unsafe-provider sentinel.
val placeholderProbe by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
tasks.register<Jar>("placeholderProbeJar") {
    description = "Builds the local-only PlaceholderAPI currency probe addon."
    group = "verification"
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("harness/build/qa-optional"))
    archiveFileName.set("MaterionePlaceholderProbe.jar")
    from(placeholderProbe.output)
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

// Long-lived JSON-Lines dry-run/apply oracle for the code-mode benchmark (issues 385, 349).
// Uses test runtimeClasspath only so zero benchmark classes enter the production plugin jar.
tasks.register<JavaExec>("benchmarkOracle") {
    description = "Runs the JSON-Lines JVM dry-run/apply oracle for code-mode benchmarks."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.materione.plugin.runtime.CodemodeBenchmarkOracleMain")
    standardInput = System.`in`
    workingDir = rootProject.projectDir
    systemProperty("materione.fixtures.root", rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath)
}
