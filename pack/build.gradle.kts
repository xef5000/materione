// Pack module: allocation table + pack compiler. The creative library
// (MIT) is the output emitter — serialized assets and the deterministic
// ZIP — behind our own graph, validation and staging logic (D6/D7).
plugins {
    id("materione.library-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":core"))
    implementation(libs.creative.api)
    implementation(libs.creative.serializer.minecraft)
    // patchMcmeta rewrites the serialized pack.mcmeta (overlay entries gain
    // min_format/max_format for the post-65 schema).
    implementation(libs.gson)
    // core's lossless layer runs SnakeYAML (compileOnly via paper-conventions,
    // provided by Paper at runtime); tests need it on the classpath.
    testImplementation(libs.snakeyaml)
    // the golden lock compiles the fixture pack through the REAL block
    // content type so pack output stays pinned to actual behavior
    testImplementation(project(":content"))
}
