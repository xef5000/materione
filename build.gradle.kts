// Root build file intentionally minimal — all conventions live in build-logic/
// (see docs/architecture/build.md). Module build files declare only their
// project dependencies.

// gradle.properties holds the bare release version. Every build is a
// -SNAPSHOT unless invoked with -Prelease (the tag-triggered release workflow
// does), so a dev jar can never be mistaken for a published one (build.md §8).
if (!providers.gradleProperty("release").isPresent) {
    allprojects {
        version = "$version-SNAPSHOT"
    }
}
