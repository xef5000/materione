package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for harness plumbing (no server boot). */
class PaperHarnessRunnerTest {

    @Test
    void logWatcherFindsMarker(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.log");
        Files.writeString(file, "first\n[Info] MATERIONE-BOOT-OK 0.1.0\nlast\n");
        var watcher = new LogWatcher(file);
        String line = watcher.awaitLine("MATERIONE-BOOT-OK", Duration.ofMillis(500));
        assertThat(line).contains("MATERIONE-BOOT-OK 0.1.0");
        assertThat(watcher.tail(2)).containsExactly("[Info] MATERIONE-BOOT-OK 0.1.0", "last");
    }

    @Test
    void logWatcherTimesOutOnMissingMarker(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.log");
        Files.writeString(file, "nothing relevant\n");
        var watcher = new LogWatcher(file);
        assertThat(watcher.awaitLine("NOPE", Duration.ofMillis(200))).isNull();
    }

    @Test
    void logWatcherToleratesMissingFile(@TempDir Path dir) throws Exception {
        var watcher = new LogWatcher(dir.resolve("does-not-exist.log"));
        assertThat(watcher.awaitLine("x", Duration.ofMillis(100))).isNull();
        assertThat(watcher.tail(5)).isEmpty();
        assertThat(watcher.readAll()).isNull();
    }

    @Test
    void consoleDriverWritesAndFlushes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ConsoleDriver console = new ConsoleDriver(out)) {
            console.send("materione version");
            console.send("stop");
        }
        assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("materione version\nstop\n");
    }

    @Test
    void harnessResultSummaryCarriesProblemsAndTail() {
        var result = new HarnessResult(
                false, List.of("marker missing"), -1, Path.of("work"), List.of("line1", "line2"));
        assertThat(result.summary())
                .contains("FAILED")
                .contains("marker missing")
                .contains("work")
                .contains("line2");
    }

    @Test
    void optionsRequireInjectedProperties() {
        // harness.workroot has a default; paper.jar/plugin.jar must be injected.
        String previous = System.clearProperty("paper.jar");
        try {
            assertThatThrownBy(HarnessOptions::fromProperties)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("paper.jar");
        } finally {
            if (previous != null) {
                System.setProperty("paper.jar", previous);
            }
        }
    }

    @Test
    void optionsTimeoutsComeFromProperties() {
        String paper = System.setProperty("paper.jar", "build/paper.jar");
        String plugin = System.setProperty("plugin.jar", "build/Materione.jar");
        String boot = System.setProperty("harness.timeout.bootSecs", "12");
        try {
            HarnessOptions options = HarnessOptions.fromProperties();
            assertThat(options.bootTimeout()).isEqualTo(Duration.ofSeconds(12));
            assertThat(options.paperJar()).isEqualTo(Path.of("build/paper.jar"));
            assertThat(options.commandTimeout()).isEqualTo(Duration.ofSeconds(60));
        } finally {
            restore("paper.jar", paper);
            restore("plugin.jar", plugin);
            restore("harness.timeout.bootSecs", boot);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
