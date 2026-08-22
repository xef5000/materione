import dev.materione.buildlogic.DownloadPaperServerTask
import dev.materione.buildlogic.PrintLatestPaperPinTask

plugins {
    id("materione.library-conventions")
    application
}

// The harness is deliberately product-independent: it boots a server, drops a
// jar in plugins/, drives the console, and asserts stable log markers
// (see harness/README.md). No :api/:core/:plugin dependencies.

application {
    mainClass.set("dev.materione.harness.PaperHarnessRunner")
}

val catalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")
val paperUrl = catalog.findVersion("paper-harness-url").get().requiredVersion
val paperSha256 = catalog.findVersion("paper-harness-sha256").get().requiredVersion

val downloadPaperServer by tasks.registering(DownloadPaperServerTask::class) {
    url.set(paperUrl)
    sha256.set(paperSha256)
    output.set(
        rootProject.gradle.gradleUserHomeDir
            .resolve("caches/materione-paper/$paperSha256/paper.jar"),
    )
}

tasks.register<PrintLatestPaperPinTask>("printLatestPaperPin") {
    paperVersion.convention(providers.gradleProperty("paperVersion"))
    // Manual maintenance task — never up-to-date, never cached.
    outputs.upToDateWhen { false }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Boots a real (pinned) Paper server with the built plugin and asserts bootstrap/command markers."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    maxParallelForks = 1
    dependsOn(":plugin:shadowJar", downloadPaperServer)
    val shadowJarPath = rootProject.projectDir.resolve("plugin/build/libs/Materione-${project.version}.jar")
    systemProperty("paper.jar", downloadPaperServer.get().output.get().asFile.absolutePath)
    systemProperty("plugin.jar", shadowJarPath.absolutePath)
    systemProperty("materione.version", project.version.toString())
    systemProperty("harness.workroot", layout.buildDirectory.dir("work").get().asFile.absolutePath)
    // The fixture corpus (grow-only, roadmap rule 2): sample packs boot on
    // real Paper with the plugin (M1 exit criterion).
    systemProperty(
        "harness.contentPacks",
        rootProject.layout.projectDirectory.dir("fixtures/packs").asFile.absolutePath,
    )
}
