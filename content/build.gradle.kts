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
            // The container profiles (gui.md §12.5.1) are one JSON file shared with the studio: the jar carries it as
            // /gui/containers.json, the same way it carries the fonts.
            srcDir(rootProject.file("shared"))
            include("gui/*.json")
        }
    }
}

// The container-agnostic ban (GuiContainerAgnosticTest, gui.md §12.5.6) reads the plugin module's GUI sources too, which
// are not on this module's classpath: declare them so a change there reruns the test instead of hitting the cache.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.layout.projectDirectory.dir("plugin/src/main/java/dev/materione/plugin/gui"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
