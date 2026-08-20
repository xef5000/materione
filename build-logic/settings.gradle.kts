pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            // Re-export the root catalog so convention plugins use the same pins.
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
