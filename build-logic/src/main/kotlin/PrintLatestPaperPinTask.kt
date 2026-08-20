package dev.materione.buildlogic

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * Manual maintenance task: queries fill.papermc.io v3 for the latest stable
 * build of a Paper version and prints ready-to-paste TOML lines for
 * gradle/libs.versions.toml. Run with:
 *
 *   ./gradlew :harness:printLatestPaperPin -PpaperVersion=1.21.4
 *
 * (The old api.papermc.io v2 API is 410 Gone; never resolve at test time.)
 */
abstract class PrintLatestPaperPinTask : DefaultTask() {

    @get:Input
    @get:Optional
    abstract val paperVersion: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun print() {
        val version = paperVersion.orNull
            ?: throw IllegalStateException("pass the version to look up: -PpaperVersion=1.21.4")
        val uri = URI("https://fill.papermc.io/v3/projects/paper/versions/$version/builds/latest")
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException(
                "fill.papermc.io returned ${response.statusCode()}: ${response.body().take(500)}",
            )
        }
        val body = response.body()
        val build = regexGroup("\"build\":\\s*(\\d+)", body)
        val url = regexGroup("\"server:default\"[\\s\\S]*?\"url\":\\s*\"([^\"]+)\"", body)
        val sha256 = regexGroup("\"server:default\"[\\s\\S]*?\"sha256\":\\s*\"([a-f0-9]{64})\"", body)
        logger.lifecycle(
            """
            |Paste into gradle/libs.versions.toml [versions]:
            |paper-harness-version = "$version"
            |paper-harness-build = "$build"
            |paper-harness-url = "$url"
            |paper-harness-sha256 = "$sha256"
            """.trimMargin(),
        )
    }

    private fun regexGroup(pattern: String, body: String): String {
        val match = Regex(pattern).find(body) ?: error("Could not extract /$pattern/ from response")
        return match.groupValues[1]
    }
}
