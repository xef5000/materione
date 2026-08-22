// Delivery module: pack hosting backends behind the core SPI (§8.4). The
// self-host HTTP backend rides creative-server (MIT, shaded); delivery
// depends on api+core only among internals (master §10).
plugins {
    id("materione.library-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
    implementation(libs.creative.api)
    implementation(libs.creative.server)
    // core's lossless layer runs SnakeYAML (compileOnly, provided by Paper);
    // tests need it on the classpath.
    testImplementation(libs.snakeyaml)
}
