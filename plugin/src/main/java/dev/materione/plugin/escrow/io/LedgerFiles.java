package dev.materione.plugin.escrow.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The file operations of one ledger, with no threading and no policy (gui-input-durability.md §5.3): the caller,
 * {@link EscrowLedgerStore}, serialises them per player on the IO executor.
 */
final class LedgerFiles {

    /** The expected size of the file did not hold: something else touched it since it was read. */
    static final class StaleView extends IOException {
        private static final long serialVersionUID = 1L;

        StaleView(String message) {
            super(message);
        }
    }

    private LedgerFiles() {}

    /** The whole file; an absent file is {@code new byte[0]} (an empty valid ledger). */
    static byte[] read(Path file) throws IOException {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException absent) {
            return new byte[0];
        } catch (java.nio.file.FileSystemException notThere) {
            // the parent is not a directory: Windows reports the file as absent, Linux as "Not a directory".
            // Treat both as absent so the platforms agree; the next append then fails as a write (MTR-0102).
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
                return new byte[0];
            }
            throw notThere;
        }
    }

    /** Whether a {@code <name>.corrupt-*} file of an earlier quarantine lies beside the ledger; returns its name. */
    static String findQuarantine(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (DirectoryStream<Path> found = Files.newDirectoryStream(dir, file.getFileName() + ".corrupt-*")) {
            for (Path candidate : found) {
                return candidate.getFileName().toString();
            }
        }
        return null;
    }

    /** Renames the ledger to {@code <name>.corrupt-<millis>} (a numeric suffix when taken); never replaces, never deletes. */
    static Path quarantine(Path file, long millis) throws IOException {
        Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + millis);
        for (int attempt = 1; Files.exists(target); attempt++) {
            target = file.resolveSibling(file.getFileName() + ".corrupt-" + millis + "-" + attempt);
        }
        Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    /**
     * Appends one record in a single write, after truncating a torn tail and writing the header of a new file.
     *
     * @param expectedSize the file size the caller last saw; a different size means another writer touched it
     * @param validLength  the length of the valid prefix: bytes beyond it are a torn tail and are cut off first
     * @param header       the header to write first when {@code validLength} is 0, else {@code null}
     * @param afterWrite   runs after the record is written and before the force (a test seam)
     * @return the file length after the append
     * @throws StaleView when the file's size is not {@code expectedSize}
     */
    static long append(Path file, long expectedSize, int validLength, byte[] header, byte[] record, boolean fsync,
            Runnable afterWrite, Runnable afterForce) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            long size = channel.size();
            if (size != expectedSize) {
                throw new StaleView("the ledger is " + size + " bytes, expected " + expectedSize);
            }
            if (size > validLength) {
                channel.truncate(validLength); // the torn tail of a kill mid-append is not a record (K17)
            }
            int length = (header == null ? 0 : header.length) + record.length;
            ByteBuffer out = ByteBuffer.allocate(length);
            if (header != null) {
                out.put(header);
            }
            out.put(record).flip();
            try {
                while (out.hasRemaining()) { // one logical write; the loop only covers a short write
                    channel.write(out);
                }
                afterWrite.run();
                if (fsync) {
                    channel.force(false);
                    afterForce.run();
                }
            } catch (IOException | RuntimeException failed) {
                // "refused" must mean "not recorded": cut the partial or unforced record back off, best effort,
                // so a caller that aborts the exit cannot be contradicted by a record that did land
                try {
                    channel.truncate(validLength);
                } catch (IOException cannotCut) {
                    failed.addSuppressed(cannotCut);
                }
                throw failed;
            }
            return validLength + length;
        }
    }

    /**
     * Replaces the file with {@code content}: a temporary sibling is written (and forced when asked), then renamed
     * over the file in one atomic step, so a kill at any instant leaves the old file whole or the new one whole.
     *
     * @param beforeMove runs after the temporary file is complete and before the rename (a test seam)
     */
    static void replace(Path file, byte[] content, boolean fsync, Runnable afterForce, Runnable beforeMove)
            throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer out = ByteBuffer.wrap(content);
            while (out.hasRemaining()) {
                channel.write(out);
            }
            if (fsync) {
                channel.force(true);
                afterForce.run();
            }
        }
        beforeMove.run();
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
