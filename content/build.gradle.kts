// Pure-JVM content module: compiles against api+core only, no Paper types
// visible (master §10). Code lands with its milestone (see docs/roadmap.md).
plugins {
    id("materione.library-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
}

sourceSets {
    main {
        // The smallcaps font source is one file shared with the studio (fonts/smallcaps/, gui.md
        // section 4): the jar carries it so SmallcapsFont reads the same pixels the webui does.
        resources {
            srcDir(rootProject.file("fonts"))
            include("**/*.txt")
        }
    }
}
