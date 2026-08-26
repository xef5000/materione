package dev.materione.core.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** One audit entry as read back from the log. */
    public record Entry(String ts, String actor, String op, String target) {}

    /**
     * The most recent entries, oldest first (the editor's audit viewer reads
     * a bounded tail, never the whole file). Unparseable lines are skipped —
     * the log is best-effort in both directions (D6).
     */
    public List<Entry> recent(int limit) {
        List<Entry> entries = new ArrayList<>();
        synchronized (lock) {
            if (!Files.isRegularFile(file)) {
                return entries;
            }
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - Math.max(1, limit));
                for (String line : lines.subList(from, lines.size())) {
                    parse(line).ifPresent(entries::add);
                }
            } catch (IOException e) {
                // an unreadable log browses as empty
            }
        }
        return entries;
    }

    private static Optional<Entry> parse(String line) {
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        var matcher = java.util.regex.Pattern
                .compile("\"(\\w+)\":(\"(?:[^\"\\\\]|\\\\.)*\")").matcher(line);
        while (matcher.find()) {
            fields.put(matcher.group(1), unquote(matcher.group(2)));
        }
        Entry entry = new Entry(
                fields.get("ts"), fields.get("actor"), fields.get("op"), fields.get("target"));
        return entry.op() == null ? Optional.empty() : Optional.of(entry);
    }

    private static String unquote(String value) {
        return value.substring(1, value.length() - 1)
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
