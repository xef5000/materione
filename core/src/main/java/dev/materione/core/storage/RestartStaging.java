package dev.materione.core.storage;

import dev.materione.api.NamespacedId;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code storage/staged-restart/} queue: changes that cannot apply hot
 * are staged here and applied at the next boot, before content load
 * (plugin.md §4.5 — restart-required changes are listed separately and
 * never silently dropped). JSONL append, compacted by full drain; the
 * directory is disposable — losing it loses only deferred work, never
 * live state.
 *
 * <p>M2 ships the machinery with no producers (no change class requires a
 * restart yet); the classification seam lands with the first producer.
 */
public final class RestartStaging {

    /** One deferred change. */
    public record Entry(NamespacedId resource, String reason) {}

    private final Path dir;
    private final Path file;
    private BufferedWriter writer;

    private RestartStaging(Path file) {
        this.file = file;
        this.dir = file.getParent();
    }

    /** Opens (or creates) the staging queue under {@code dir}; never throws for content problems. */
    public static RestartStaging open(Path dir) {
        RestartStaging staging = new RestartStaging(dir.resolve("staged.jsonl"));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return staging; // add() will surface the write failure per entry
        }
        return staging;
    }

    /**
     * Loads pending entries; corrupt lines are dropped (fail-soft). The
     * returned list is a snapshot — call after loading to drain at boot.
     */
    public synchronized List<Entry> load() {
        var out = new ArrayList<Entry>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                parseEntry(line).ifPresent(out::add);
            }
        } catch (IOException e) {
            return List.of(); // unreadable queue behaves as empty; writes still append
        }
        return out;
    }

    /** Appends one entry (durable on return); failures surface as false, never throw. */
    public synchronized boolean stage(NamespacedId resource, String reason) {
        try {
            if (writer == null) {
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            writer.write(toJson(resource, reason));
            writer.newLine();
            writer.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Drains the queue: everything staged so far is considered applied. */
    public synchronized int clear() {
        try {
            closeWriter();
            Files.createDirectories(dir);
            Path tmp = dir.resolve("staged.jsonl.tmp");
            Files.writeString(tmp, "", StandardCharsets.UTF_8);
            tryAtomicMove(tmp);
            writer = null;
        } catch (IOException e) {
            return -1; // caller reports; entries stay queued (safe direction)
        }
        return 0;
    }

    /** Closes the writer (shutdown path). */
    public synchronized void close() {
        closeWriter();
    }

    // --- helpers -------------------------------------------------------------

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // shutdown path: nothing actionable
            }
            writer = null;
        }
    }

    private void tryAtomicMove(Path tmp) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Minimal JSON line: {"resource":"ns:id","reason":"..."} with escaped strings. */
    private static String toJson(NamespacedId resource, String reason) {
        return "{\"resource\":\"" + resource + "\",\"reason\":\"" + quote(reason == null ? "" : reason) + "\"}";
    }

    private static Optional<Entry> parseEntry(String line) {
        int resStart = line.indexOf("\"resource\":\"");
        int resEnd = resStart < 0 ? -1 : line.indexOf('"', resStart + 12);
        if (resStart < 0 || resEnd < 0) {
            return Optional.empty();
        }
        try {
            NamespacedId resource = NamespacedId.parse(line.substring(resStart + 12, resEnd));
            int reasonStart = line.indexOf("\"reason\":\"");
            String reason = reasonStart < 0 ? "" : unquote(line.substring(reasonStart + 10));
            return Optional.of(new Entry(resource, reason));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // torn or foreign line
        }
    }

    private static String unquote(String rest) {
        int end = rest.indexOf('"');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static String quote(String text) {
        var out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
