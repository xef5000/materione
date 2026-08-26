package dev.materione.core.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Append-only record of who changed what through the editor
 * ({@code storage/audit.log}, JSONL — plugin.md §7 "Web sessions, audit log").
 * Best-effort by design: an audit write failure never blocks the operation
 * it describes (D6); it surfaces as a false return the caller may log.
 */
public final class AuditLog {

    private final Path file;
    private final Object lock = new Object();

    public AuditLog(Path file) {
        this.file = file;
    }

    /** Records one editor action; returns false when the write failed. */
    public boolean record(String actor, String op, String target) {
        String line = "{\"ts\":\"" + Instant.now() + "\""
                + ",\"actor\":" + quote(actor) + ",\"op\":" + quote(op)
                + ",\"target\":" + quote(target) + "}\n";
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
