package dev.materione.plugin.escrow.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The capability check's file half (gui-input-durability.md §5.8): can {@code storage/escrow/} be created and written?
 * It creates the directory when it is missing (the ledger creates it lazily otherwise, and an empty directory is all
 * this leaves), writes a small file and deletes it. File IO, so it runs on the IO executor, never on an owner thread;
 * it lives in this package for the same reason the ledger writer does (the runtime half may not touch a file).
 */
public final class DirectoryProbe {

    private DirectoryProbe() {}

    /**
     * Runs the probe.
     *
     * @return empty when the directory is writable, otherwise a short cause for the diagnostic
     */
    public static Optional<String> run(Path directory) {
        Path probe = null;
        try {
            Files.createDirectories(directory);
            probe = Files.createTempFile(directory, ".probe-", ".tmp");
            Files.write(probe, new byte[] {1});
            return Optional.empty();
        } catch (IOException | RuntimeException failed) {
            return Optional.of(failed.getClass().getSimpleName() + ": " + failed.getMessage());
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // a leftover .probe-*.tmp is harmless and the next probe makes its own
                }
            }
        }
    }
}
