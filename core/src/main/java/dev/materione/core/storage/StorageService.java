package dev.materione.core.storage;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.diagnostics.DiagnosticCodes;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Creates and validates the plugin data layout (master §7.1):
 * {@code storage/} (durable), {@code generated/} (disposable),
 * {@code logs/}. The storage format marker is a properties file whose format
 * version only ever moves forward; readers accept N-1. Fail-soft: directory
 * problems degrade with a diagnostic, never crash the boot.
 */
public final class StorageService {

    /** Storage container format version (the allocation table lands at M1). */
    public static final int FORMAT_VERSION = 1;

    /** Initialized storage state. */
    public record StorageState(
            Path contentPacksDir,
            Path storageDir,
            Path generatedDir,
            Path logsDir,
            int formatVersion,
            List<Diagnostic> diagnostics) {
        public StorageState {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Initializes the data layout under the plugin data folder. Never throws. */
    public StorageState init(Path dataFolder) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Path contentPacks = dataFolder.resolve("content").resolve("packs");
        Path storage = dataFolder.resolve("storage");
        Path generated = dataFolder.resolve("generated");
        Path logs = dataFolder.resolve("logs");

        try {
            Files.createDirectories(contentPacks);
            Files.createDirectories(storage);
            Files.createDirectories(generated);
            Files.createDirectories(logs);
        } catch (IOException e) {
            return new StorageState(contentPacks, storage, generated, logs, FORMAT_VERSION, List.of(new Diagnostic(
                    DiagnosticCodes.STORAGE_INIT_FAILED, Severity.ERROR,
                    "could not create storage directories: " + e.getMessage(),
                    null, null, "check permissions on " + dataFolder)));
        }

        int formatVersion = ensureFormatMarker(storage);
        return new StorageState(contentPacks, storage, generated, logs, formatVersion, List.of());
    }

    private static int ensureFormatMarker(Path storageDir) {
        Path marker = storageDir.resolve("format.properties");
        if (!Files.exists(marker)) {
            writeFormatMarker(marker, FORMAT_VERSION);
            return FORMAT_VERSION;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            properties.load(in);
        } catch (IOException e) {
            // Unreadable marker: rewrite it at the current version.
            writeFormatMarker(marker, FORMAT_VERSION);
            return FORMAT_VERSION;
        }
        String raw = properties.getProperty("formatVersion");
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            writeFormatMarker(marker, FORMAT_VERSION);
            return FORMAT_VERSION;
        }
    }

    private static void writeFormatMarker(Path marker, int version) {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", Integer.toString(version));
        try (OutputStream out = Files.newOutputStream(marker)) {
            properties.store(out, "Materione storage format marker (durable; see master architecture 7.1)");
        } catch (IOException e) {
            // Best effort; init() surfaces directory-level problems already.
        }
    }
}
