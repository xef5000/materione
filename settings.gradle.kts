pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provision a JDK 21 toolchain on machines without one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "materione"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
            // paper-api's transitives (brigadier, bungeecord-chat, ...) live
            // here too — the filter lists exactly the groups we expect from
            // the Paper ecosystem, nothing else.
            content {
                includeGroupByRegex("io\\.papermc.*")
                includeGroupByRegex("com\\.mojang.*")
                includeGroup("net.md-5")
            }
        }
    }
}

// Product modules (master architecture §10, as amended by M0).
include(
    ":api",
    ":core",
    ":platform",
    ":plugin",
    ":content",
    ":behavior",
    ":pack",
    ":delivery",
    ":web",
    ":importers",
    ":integrations",
)
// Build-infrastructure modules — not product modules, not on any runtime classpath.
include(
    ":testing",
    ":harness",
)
// Deliberately NOT Gradle modules:
//   webui/    — TypeScript SPA; build tooling lands with M4/M5.
//   fixtures/ — data-only content corpus (roadmap rule 2).
