package dev.materione.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageServiceTest {

    @TempDir
    Path tempDir;

    private final StorageService service = new StorageService();

    @Test
    void createsLayoutAndFormatMarker() {
        var state = service.init(tempDir);
        assertThat(state.diagnostics()).isEmpty();
        assertThat(state.storageDir()).isEqualTo(tempDir.resolve("storage"));
        assertThat(Files.isDirectory(state.storageDir())).isTrue();
        assertThat(Files.isDirectory(state.generatedDir())).isTrue();
        assertThat(Files.isDirectory(state.logsDir())).isTrue();
        assertThat(state.formatVersion()).isEqualTo(1);
        assertThat(tempDir.resolve("storage/format.properties")).exists();
    }

    @Test
    void initIsIdempotent() {
        service.init(tempDir);
        var second = service.init(tempDir);
        assertThat(second.formatVersion()).isEqualTo(1);
        assertThat(second.diagnostics()).isEmpty();
    }

    @Test
    void garbageMarkerIsRepairedAtCurrentVersion() throws Exception {
        service.init(tempDir);
        Files.writeString(tempDir.resolve("storage/format.properties"), "formatVersion=banana");
        var state = service.init(tempDir);
        assertThat(state.formatVersion()).isEqualTo(1);
    }
}
