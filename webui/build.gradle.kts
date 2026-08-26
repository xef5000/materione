// The editor SPA (web.md §6): TypeScript + Svelte 5 + Vite, built to static
// files and embedded into the plugin jar so direct mode works offline. The
// node-gradle plugin provisions a pinned Node hermetically — no local Node
// install required, CI included. Protocol correctness is pinned by the golden
// fixture test (test/protocol.spec.ts) against the same file the JVM uses.

plugins {
    base
    id("com.github.node-gradle.node") version "7.1.0"
}

// Pinned toolchain — bump deliberately; the SPA must build identically on dev
// machines and CI (both provision it hermetically, no local Node required).
val nodeVersion = "22.17.0"

node {
    download = true
    version = nodeVersion
}

// node-gradle resolves npm.cmd itself, but script shims spawned BY npm
// (vitest, vite) look up bare `node` on PATH — which does not contain the
// downloaded distribution. Put the platform-specific bin dir on every npm
// task's environment explicitly.
val isWindows = providers.systemProperty("os.name").get().lowercase().contains("windows")
val nodeBinDir = layout.projectDirectory
    .dir(".gradle/nodejs/node-v$nodeVersion-" + if (isWindows) "win-x64" else "linux-x64")
tasks.withType(com.github.gradle.node.npm.task.NpmTask::class.java).configureEach {
    val separator = if (isWindows) ";" else ":"
    environment.put("PATH", nodeBinDir.asFile.absolutePath + separator + (System.getenv("PATH") ?: ""))
}

// `npm ci` would demand a committed lockfile; plain install generates it on
// the first run and stays reproducible afterwards via the lockfile we commit.
tasks.named("npmInstall") {
    inputs.file("package.json")
    outputs.file(layout.projectDirectory.file("package-lock.json"))
}

val npmTest = tasks.named("npm_run_test")
val npmBuild = tasks.named("npm_run_build")
val npmCheck = tasks.named("npm_run_check")

tasks.named("check") {
    // The protocol fixture spec runs with every build — drift fails both sides.
    dependsOn(npmTest)
    // svelte-check type-checks components (vite/esbuild only strips types —
    // an undefined template reference like a missing store import compiles
    // fine and explodes at render time; this gate catches it).
    dependsOn(npmCheck)
}

tasks.register("assembleSpa") {
    group = "build"
    description = "Builds the editor SPA into dist/."
    dependsOn(npmBuild)
}
