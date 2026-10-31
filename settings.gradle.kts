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
    // PREFER_SETTINGS rather than FAIL_ON_PROJECT_REPOS: the :webui
    // node-gradle plugin registers a Node.js-distribution repository on its
    // project at configure time, which strict mode rejects outright. With
    // PREFER_SETTINGS that registration is ignored (warning only) and the
    // identical Ivy layout below does the resolving. Product dependencies
    // remain pinned by these settings repositories alone.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        // Node.js distribution for :webui's pinned toolchain download.
        ivy {
            name = "Node.js distribution"
            url = uri("https://nodejs.org/dist")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources { artifact() }
            content { includeGroup("org.nodejs") }
        }
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
        maven("https://repo.extendedclip.com/releases/") {
            name = "extendedclip"
            // PlaceholderAPI (compileOnly in :plugin for the %materione_*% expansion, logic.md §3.3); the
            // server provides the jar at runtime, so nothing here is shaded.
            content {
                includeGroup("me.clip")
            }
        }
        // Vault's provider API (integrations, compileOnly). Published on JitPack only; the
        // filter admits the one group, so nothing else resolves from here.
        maven("https://jitpack.io") {
            name = "jitpack"
            content {
                includeGroup("com.github.MilkBowl")
            }
        }
        maven("https://repo.nexomc.com/releases/") {
            name = "nexomc"
            // The Nexo-maintained creative fork keeps upstream's coordinates
            // (team.unnamed) but publishes only here — filter to that group.
            content {
                includeGroup("team.unnamed")
            }
        }
    }
}

// Product modules (master architecture §10, as amended by M0).
include(
    ":api",
    ":core",
    ":platform",
    ":modern",
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
    // M3: the editor SPA. Not a product Java module — a Node/TypeScript build
    // whose static output is embedded into the shaded jar (plugin resources
    // under webui/) so direct mode works fully offline.
    ":webui",
)
// Deliberately NOT Gradle modules:
//   fixtures/ — data-only content corpus (roadmap rule 2).
