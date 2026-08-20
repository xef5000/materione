plugins {
    `kotlin-dsl`
}

dependencies {
    // Shadow plugin classes must be on the script-compile classpath for
    // materione.plugin-conventions to reference shadowJar configuration.
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.6.1")
}
