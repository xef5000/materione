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
    void cursorReturnsFreshPayloadNotTheFirstMatchingLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.log");
        Files.writeString(file, "REPLY old\n");
        var watcher = new LogWatcher(file);
        var cursor = watcher.mark();
        Files.writeString(file, "REPLY new\n", java.nio.file.StandardOpenOption.APPEND);
        assertThat(watcher.awaitLine("REPLY", Duration.ofMillis(500), cursor)).isEqualTo("REPLY new");
        assertThat(watcher.awaitLine("REPLY", Duration.ofMillis(20), watcher.mark())).isNull();
    }

    @Test
    void cursorAcceptsReplacedLogButNotPreviousBoot(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.log");
        Files.writeString(file, "previous boot\nDone (old)\n");
        var watcher = new LogWatcher(file);
        var cursor = watcher.mark();
        assertThat(watcher.awaitLine("Done (", Duration.ofMillis(20), cursor)).isNull();
        Files.writeString(file, "new boot\nDone (new)\n");
        assertThat(watcher.awaitLine("Done (", Duration.ofMillis(500), cursor)).isEqualTo("Done (new)");
    }

    @Test
    void replyBeforeConsoleSendReturnsIsNotLost(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.log");
        var watcher = new LogWatcher(file);
        var cursor = watcher.mark();
        var out = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                Files.writeString(file, "APPROVED\n");
            }
        };
        try (var console = new ConsoleDriver(out)) {
            console.send("approve");
            assertThat(watcher.awaitLine("APPROVED", Duration.ofMillis(500), cursor)).isEqualTo("APPROVED");
        }
    }

    @Test
    void exitedChildFailsWithoutWaitingForBootDeadline(@TempDir Path dir) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process child = new ProcessBuilder(javaExecutable.toString(), "-version")
                .redirectErrorStream(true).redirectOutput(dir.resolve("child.log").toFile()).start();
        try {
            assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var watcher = new LogWatcher(dir.resolve("latest.log"));
            watcher.watch(child);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> assertThatThrownBy(() -> watcher.awaitLine("Done (", Duration.ofMinutes(5)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("server exited").hasMessageContaining("exit=0")
                    .hasMessageContaining("Done ("));
        } finally {
            PaperHarnessRunner.killTree(child);
        }
    }

    @Test
    void unreadableLogIsNotReportedAsMissing(@TempDir Path dir) {
        var watcher = new LogWatcher(dir);
        assertThatThrownBy(watcher::readAll).isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("could not read harness log");
    }

    @Test
    void liveAppendCannotExposeHalfAUtf8CharacterOrMarker(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("latest.log");
        Files.write(file, new byte[] {'o', 'k', '\n', 'R', 'E', 'P', 'L', 'Y', ' ', (byte) 0xe2, (byte) 0x80});
        var watcher = new LogWatcher(file);
        assertThat(watcher.readAll()).isEqualTo("ok\n");
        var cursor = watcher.mark();
        assertThat(watcher.awaitLine("REPLY", Duration.ofMillis(20), cursor)).isNull();
        Files.write(file, new byte[] {(byte) 0x94, '\n'}, java.nio.file.StandardOpenOption.APPEND);
        assertThat(watcher.awaitLine("REPLY", Duration.ofMillis(20), cursor)).isNull();
        Files.writeString(file, "REPLY fresh\n", java.nio.file.StandardOpenOption.APPEND);
        assertThat(watcher.awaitLine("REPLY", Duration.ofMillis(500), cursor)).isEqualTo("REPLY fresh");
        assertThat(watcher.readAll()).contains("REPLY \u2014");
    }

    @Test
    void redirectedServerStreamsAreUtf8EvenOnWindows(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("stub.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", StubServer.class.getName());
        String resource = StubServer.class.getName().replace('.', '/') + ".class";
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest);
                var input = StubServer.class.getClassLoader().getResourceAsStream(resource)) {
            output.putNextEntry(new java.util.jar.JarEntry(resource));
            java.util.Objects.requireNonNull(input).transferTo(output);
            output.closeEntry();
        }
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var options = new HarnessOptions(jar, jar, javaExecutable, dir.resolve("work"), "test",
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), null, null, null);
        var workspace = ServerWorkspace.create(options, "stub");
        var log = new LogWatcher(workspace.logFile);
        Process child = PaperHarnessRunner.start(options, workspace, log);
        try {
            assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
            assertThat(new LogWatcher(workspace.stdoutFile).readAll())
                    .contains("stdout \u2014", "stderr \u2014");
        } finally {
            PaperHarnessRunner.killTree(child);
        }
    }

    public static final class StubServer {
        public static void main(String[] args) {
            System.out.println("stdout \u2014");
            System.err.println("stderr \u2014");
        }
    }

    @Test
    void harnessFailureRedactsCapabilitiesAndPreservesCause(@TempDir Path dir) {
        var result = new HarnessResult(false,
                List.of("RPC failed: timeout http://localhost/?s=capability#wrapping"),
                7, dir, List.of("MATERIONE-EDITOR http://localhost/s/secret#key"));
        assertThat(result.summary()).contains("RPC failed", "timeout", "exit 7", "REDACTED")
                .doesNotContain("capability", "wrapping", "secret", "#key");
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
