package dev.materione.buildlogic

import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Downloads the pinned Paper server jar and verifies its sha256. Hermetic:
 * never calls an API — the absolute URL and checksum are pinned in
 * gradle/libs.versions.toml (see docs/architecture/build.md for the bump
 * procedure via :harness:printLatestPaperPin).
 */
abstract class DownloadPaperServerTask : DefaultTask() {

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun download() {
        val expected = sha256.get()
        val target = output.get().asFile
        if (target.exists() && hashOf(target) == expected) {
            logger.lifecycle("Paper server jar cached and verified: {}", target)
            return
        }
        target.parentFile.mkdirs()
        logger.lifecycle("Downloading Paper server jar from {}", url.get())
        val tmp = target.resolveSibling(target.name + ".part")
        URI(url.get()).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = hashOf(tmp)
        check(actual == expected) {
            "Paper server jar sha256 mismatch: expected $expected, got $actual"
        }
        check(tmp.renameTo(target)) { "Failed to move $tmp to $target" }
        logger.lifecycle("Downloaded and verified: {}", target)
    }

    private fun hashOf(file: java.io.File): String =
        MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
}
