import dev.materione.buildlogic.DownloadPaperServerTask
import dev.materione.buildlogic.PrintLatestPaperPinTask

plugins {
    id("materione.library-conventions")
    application
}

// The harness is deliberately product-independent: it boots a server, drops a
// jar in plugins/, drives the console, and asserts stable log markers
// (see harness/README.md). No :api/:core/:plugin dependencies — with one M3
// exception: :web's protocol package, because the editor scenario plays the
// browser side of the wire protocol (R8 exempts harness as build
// infrastructure; this edge must never grow toward any other product module).

application {
    mainClass.set("dev.materione.harness.PaperHarnessRunner")
}

dependencies {
    implementation(project(":web"))
    // The editor scenario builds JSON on the browser side of the wire; the
    // harness JVM has no server to provide Gson, so it carries its own.
    implementation(libs.gson)
    // (the relay IT's FakeMailbox comes from :testing's test fixtures —
    // wired into testImplementation by the library conventions)
}

// A test-only Paper plugin observes native state in the opt-in floor scenario; it is never shipped.
val richTitleProbe = sourceSets.create("richTitleProbe")
dependencies {
    add(richTitleProbe.compileOnlyConfigurationName, libs.paper.api)
}
val richTitleProbeJar by tasks.registering(Jar::class) {
    archiveClassifier.set("rich-title-probe")
    from(richTitleProbe.output)
}

// A second test-only Paper plugin opens a hopper and a dispenser through the legacy path (gui.md 12.2.6, #289).
// It rides in every integration run (the container window IT) and is installed by hand on the 26.x probe.
val containerProbe = sourceSets.create("containerProbe")
dependencies {
    add(containerProbe.compileOnlyConfigurationName, libs.paper.api)
}
val containerProbeJar by tasks.registering(Jar::class) {
    archiveClassifier.set("container-probe")
    from(containerProbe.output)
}

// A third test-only plugin (#306, row R of docs/architecture/gui-input-durability.md): a fake-connection player, a crash gate
// and the K-row primitives. Installed by the input-durability scenarios; never shipped.
val durabilityProbe = sourceSets.create("durabilityProbe")
dependencies {
    add(durabilityProbe.compileOnlyConfigurationName, libs.paper.api)
}
val durabilityProbeJar by tasks.registering(Jar::class) {
    archiveClassifier.set("durability-probe")
    from(durabilityProbe.output)
}

// The Folia probe plugin (#306, row R2): declares folia-supported and runs its experiment on the player's region thread.
val foliaProbe = sourceSets.create("foliaProbe")
dependencies {
    add(foliaProbe.compileOnlyConfigurationName, libs.paper.api)
}
val foliaProbeJar by tasks.registering(Jar::class) {
    archiveClassifier.set("folia-probe")
    from(foliaProbe.output)
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

// Folia (#306, row R2): the same download task, two pins (1.21.4 build 6, 26.1.2 build 8). Opt-in use only.
val foliaHarnessSha256 = catalog.findVersion("folia-harness-sha256").get().requiredVersion
val downloadFoliaServer by tasks.registering(DownloadPaperServerTask::class) {
    url.set(catalog.findVersion("folia-harness-url").get().requiredVersion)
    sha256.set(foliaHarnessSha256)
    output.set(
        rootProject.gradle.gradleUserHomeDir
            .resolve("caches/materione-paper/$foliaHarnessSha256/folia.jar"),
    )
}
val foliaProbeSha256 = catalog.findVersion("folia-probe-sha256").get().requiredVersion
val downloadFoliaProbeServer by tasks.registering(DownloadPaperServerTask::class) {
    url.set(catalog.findVersion("folia-probe-url").get().requiredVersion)
    sha256.set(foliaProbeSha256)
    output.set(
        rootProject.gradle.gradleUserHomeDir
            .resolve("caches/materione-paper/$foliaProbeSha256/folia.jar"),
    )
}

tasks.register<PrintLatestPaperPinTask>("printLatestPaperPin") {
    paperVersion.convention(providers.gradleProperty("paperVersion"))
    pinPrefix.convention(providers.gradleProperty("pinPrefix"))
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
    dependsOn(":plugin:shadowJar", downloadPaperServer, containerProbeJar, durabilityProbeJar)
    val shadowJarPath = rootProject.projectDir.resolve("plugin/build/libs/Materione-${project.version}.jar")
    systemProperty("paper.jar", downloadPaperServer.get().output.get().asFile.absolutePath)
    systemProperty("plugin.jar", shadowJarPath.absolutePath)
    systemProperty("materione.version", project.version.toString())
    systemProperty("harness.containerProbeJar", containerProbeJar.get().archiveFile.get().asFile.absolutePath)
    inputs.file(containerProbeJar.flatMap { it.archiveFile })
    systemProperty("harness.durabilityProbeJar", durabilityProbeJar.get().archiveFile.get().asFile.absolutePath)
    inputs.file(durabilityProbeJar.flatMap { it.archiveFile })
    systemProperty("harness.workroot", layout.buildDirectory.dir("work").get().asFile.absolutePath)
    // The container GUI floor (#292, row P): the product opens a hopper and a dispenser for a protocol client. It needs
    // `npm ci` in harness/protocol, so it is opt-in like the rich-title floor.
    if (providers.gradleProperty("harness.containerGuiFloor").orNull == "true") {
        testLogging.showStandardStreams = true
        systemProperty("harness.containerGuiFloor", "true")
        systemProperty("harness.protocolDir", layout.projectDirectory.dir("protocol").asFile.absolutePath)
        inputs.files(fileTree(layout.projectDirectory.dir("protocol")) { exclude("node_modules/**") })
    }
    // The input-durability probes (#306): a fake-connection player, a crash gate and restart on the Paper floor, and the
    // death scenario. Each boots the server several times, so they are opt-in like the floor tests above.
    if (providers.gradleProperty("harness.inputDurability").orNull == "true") {
        testLogging.showStandardStreams = true
        systemProperty("harness.inputDurability", "true")
    }
    // The Folia probe (#306, row R2): a Folia server jar (sha256-pinned, see libs.versions.toml) and a protocol client,
    // which needs `npm ci --ignore-scripts` in harness/protocol. Opt-in.
    if (providers.gradleProperty("harness.folia").orNull == "true") {
        dependsOn(downloadFoliaServer, foliaProbeJar)
        testLogging.showStandardStreams = true
        systemProperty("harness.folia", "true")
        systemProperty("harness.folia.jar", downloadFoliaServer.get().output.get().asFile.absolutePath)
        systemProperty("harness.foliaProbeJar", foliaProbeJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("harness.protocolDir", layout.projectDirectory.dir("protocol").asFile.absolutePath)
        inputs.file(foliaProbeJar.flatMap { it.archiveFile })
        inputs.files(fileTree(layout.projectDirectory.dir("protocol")) { exclude("node_modules/**") })
    }
    if (providers.gradleProperty("harness.richTitleFloor").orNull == "true") {
        dependsOn(richTitleProbeJar)
        testLogging.showStandardStreams = true
        systemProperty("harness.richTitleFloor", "true")
        systemProperty("harness.richTitleProbeJar", richTitleProbeJar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("harness.protocolDir", layout.projectDirectory.dir("protocol").asFile.absolutePath)
        systemProperty("harness.paperSha256", paperSha256)
        inputs.file(richTitleProbeJar.flatMap { it.archiveFile })
        inputs.files(fileTree(layout.projectDirectory.dir("protocol")) { exclude("node_modules/**") })
    }
    // Ports for the boots this run starts. The defaults are the CI pins (25599, 8089, 8090); a local run
    // whose ports another server holds passes -Pharness.serverPort=… -Pharness.editorPort=… -Pharness.deliveryPort=…
    for (port in listOf("harness.serverPort", "harness.editorPort", "harness.deliveryPort")) {
        providers.gradleProperty(port).orNull?.let { systemProperty(port, it) }
    }
    // The fixture corpus (grow-only, roadmap rule 2): sample packs boot on
    // real Paper with the plugin (M1 exit criterion).
    systemProperty(
        "harness.contentPacks",
        rootProject.layout.projectDirectory.dir("fixtures/packs").asFile.absolutePath,
    )
    // The jars/paths above reach the tests as strings — without these inputs
    // a rebuilt plugin jar or edited fixture pack would leave the task
    // up-to-date and report a stale green.
    inputs.file(shadowJarPath)
    inputs.dir(rootProject.layout.projectDirectory.dir("fixtures/packs"))
}
