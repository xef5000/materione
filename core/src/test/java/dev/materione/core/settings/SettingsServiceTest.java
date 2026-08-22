package dev.materione.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsServiceTest {

    @TempDir
    Path tempDir;

    private final SettingsService service = new SettingsService();

    @Test
    void missingFileCreatesDefaults() {
        var state = service.load(tempDir);
        assertThat(state.version()).isEqualTo(1);
        assertThat(state.diagnostics()).isEmpty();
        assertThat(tempDir.resolve("settings.yml")).exists();
        assertThat(state.getBoolean("debug", true)).isFalse();
    }

    @Test
    void unknownKeysAndOrderSurviveRoundTrip() throws IOException {
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("version", 1);
        written.put("future.addon", "keep me");
        written.put("another", 42);
        SettingsService.write(tempDir.resolve("settings.yml"), written);

        var state = service.load(tempDir);
        assertThat(state.diagnostics()).isEmpty();
        assertThat(state.values()).containsKeys("future.addon", "another");
        assertThat(state.values().keySet())
                .containsExactly("version", "future.addon", "another");

        // Lossless round-trip (master §5.6): dump(load(file)) is stable.
        String dumped = SettingsService.dump(state.values());
        assertThat(dumped).isEqualTo(Files.readString(tempDir.resolve("settings.yml")));
    }

    @Test
    void unparseableFileDegradesToDefaultsWithDiagnostic() throws IOException {
        Files.writeString(tempDir.resolve("settings.yml"), "{ [ this is: not valid yaml");
        var state = service.load(tempDir);

        assertThat(state.version()).isEqualTo(1);
        assertThat(state.getBoolean("debug", true)).isFalse();
        assertThat(state.diagnostics()).singleElement()
                .satisfies(d -> {
                    assertThat(d.code().code()).isEqualTo("MTR-0004");
                    assertThat(d.severity()).isEqualTo(Severity.ERROR);
                });
        // The broken file is preserved, a fresh default written.
        assertThat(tempDir.resolve("settings.yml.broken-1")).exists();
        assertThat(tempDir.resolve("settings.yml"))
                .hasContent(SettingsService.dump(SettingsService.defaults()));
    }

    @Test
    void missingVersionWarnsButProceeds() throws IOException {
        Files.writeString(tempDir.resolve("settings.yml"), "debug: true\n");
        var state = service.load(tempDir);

        assertThat(state.version()).isEqualTo(1);
        assertThat(state.getBoolean("debug", false)).isTrue();
        assertThat(state.diagnostics()).singleElement()
                .satisfies(d -> assertThat(d.code().code()).isEqualTo("MTR-0003"));
    }

    @Test
    void futureVersionWarnsAndKeepsValues() throws IOException {
        Files.writeString(tempDir.resolve("settings.yml"), "version: 99\nnewkey: present\n");
        var state = service.load(tempDir);

        assertThat(state.version()).isEqualTo(99);
        assertThat(state.getString("newkey", "missing")).isEqualTo("present");
        assertThat(state.diagnostics()).singleElement()
                .satisfies(d -> assertThat(d.code().code()).isEqualTo("MTR-0003"));
    }

    @Test
    void defaultsDumpMatchesGolden() throws IOException {
        Path dumped = tempDir.resolve("defaults.yml");
        Files.writeString(dumped, SettingsService.dump(SettingsService.defaults()));
        if (dev.materione.testing.golden.GoldenFiles.regenEnabled()) {
            dev.materione.testing.golden.GoldenFiles.writeGolden(dumped, "settings-default.yml");
        }
        dev.materione.testing.golden.GoldenFiles.assertMatchesGolden(dumped, "settings-default.yml");
    }
}
