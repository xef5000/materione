plugins {
    id("materione.paper-conventions")
}

dependencies {
    api(project(":api"))
    // SnakeYAML is compileOnly via paper-conventions (runtime provided by
    // Paper); tests of the lossless layer need it on the test classpath too.
    testImplementation(libs.snakeyaml)
}
