// Assembly-module conventions: the one module that produces the distributable
// plugin jar. plugin.yml token replacement, generated version source, Shadow
// wiring. Currently ZERO shaded runtime dependencies — the wiring exists so
// future deps shade + relocate correctly from day one.
//
// Written without Kotlin-DSL task accessors (they don't generate reliably in
// precompiled script plugins); plain named-task references only.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.materione.buildlogic.GenerateVersionSourceTask
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    java
    id("materione.paper-conventions")
    id("com.gradleup.shadow")
}

val generatedVersionDir = layout.buildDirectory.dir("generated/sources/version/main")

val generateVersionSource = tasks.register<GenerateVersionSourceTask>("generateVersionSource") {
    version.set(project.version.toString())
    outputDir.set(generatedVersionDir)
}

configure<SourceSetContainer> {
    named("main") {
        java.srcDir(generatedVersionDir)
    }
}
tasks.named("compileJava") { dependsOn(generateVersionSource) }

val pluginVersion = project.version.toString()

tasks.named<ProcessResources>("processResources") {
    // ReplaceTokens (@token@) rather than Groovy-template expand(): the
    // expand() overloads churned across Gradle versions; this is stable.
    //
    // Config-cache note: `pluginVersion` is a property of the *script class*,
    // and this action runs at execution time — reading it from inside the
    // filesMatching lambda would serialize the script object. Copy the value
    // into a local first so the stored lambda captures only a Map.
    val versionTokens = mapOf("materioneVersion" to pluginVersion)
    filesMatching("plugin.yml") {
        filter<org.apache.tools.ant.filters.ReplaceTokens>(
            "tokens" to versionTokens,
        )
    }
}

tasks.named<Jar>("jar") {
    // The shaded jar is the only unclassified artifact; the plain jar is
    // kept for tests but never shipped.
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveFileName.set("Materione-${project.version}.jar")
    mergeServiceFiles()
    manifest {
        attributes(
            "Implementation-Title" to "Materione",
            "Implementation-Version" to project.version.toString(),
        )
    }
    // Shaded deps relocate under materione.libs.* — creative is the first
    // (M1): the resource-pack emitter, plus its transitive gson (Paper ships
    // its own gson; shading ours unrelocated would risk class conflicts).
    // Keeps external APIs out of the classpath other plugins see, and ours
    // stable across upgrades.
    relocate("team.unnamed", "materione.libs.team.unnamed")
    relocate("com.google.gson", "materione.libs.com.google.gson")
    // M3: the embedded editor listener (Undertow + its XNIO/JBoss runtime),
    // per build.md §4. Only dev.materione.web may reference these (R8), so
    // relocation is invisible to every other module.
    relocate("io.undertow", "materione.libs.io.undertow")
    relocate("org.xnio", "materione.libs.org.xnio")
    relocate("org.jboss", "materione.libs.org.jboss")
    relocate("org.wildfly", "materione.libs.org.wildfly")
}
