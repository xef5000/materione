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
 *
 * <p>An entry may carry, additively, where the change came from ({@code origin}), whether the owner granted
 * it ({@code approval}), the transaction it belongs to, and the code-mode program that produced it
 * ({@code program}, size-bounded, with a {@code digest} of the whole text) — codemode.md section 5.6, C13.
 * Entries written before those fields existed read as before: origin {@code editor}, everything else absent.
 */
public final class AuditLog {

    /** The origin an entry has when none was recorded. */
    public static final String DEFAULT_ORIGIN = "editor";

    private final Path file;
    private final Object lock = new Object();

    public AuditLog(Path file) {
        this.file = file;
    }

    /**
     * The optional context of an entry. {@code program} is already bounded by the caller and {@code programLength}
     * is the length of the text the digest covers, so a truncated program is recognizable (its length is less than
     * {@code programLength}).
     */
    public record Details(
            String origin, String approval, String transaction, String program, String digest, Integer programLength) {

        public static Details of(String origin, String approval, String transaction) {
            return new Details(origin, approval, transaction, null, null, null);
        }
    }

    /** Records one editor action; returns false when the write failed. */
    public boolean record(String actor, String op, String target) {
        return record(actor, op, target, null);
    }

    /** {@link #record(String, String, String)} with the additive context; null or empty fields are left out. */
    public boolean record(String actor, String op, String target, Details details) {
        StringBuilder line = new StringBuilder();
        line.append("{\"ts\":\"").append(Instant.now()).append("\"")
                .append(",\"actor\":").append(quote(actor)).append(",\"op\":").append(quote(op))
                .append(",\"target\":").append(quote(target));
        if (details != null) {
            optional(line, "origin", details.origin());
            optional(line, "approval", details.approval());
            optional(line, "transaction", details.transaction());
            optional(line, "program", details.program());
            optional(line, "digest", details.digest());
            if (details.programLength() != null) {
                line.append(",\"programLength\":").append(details.programLength());
            }
        }
        line.append("}\n");
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private static void optional(StringBuilder line, String name, String value) {
        if (value != null && !value.isEmpty()) {
            line.append(",\"").append(name).append("\":").append(quote(value));
        }
    }

    /**
     * One audit entry as read back from the log. {@code origin} is never null ({@link #DEFAULT_ORIGIN} for an
     * older entry); the other additive fields are null when the entry does not carry them.
     */
    public record Entry(
            String ts,
            String actor,
            String op,
            String target,
            String origin,
            String approval,
            String transaction,
            String program,
            String digest,
            Integer programLength) {

        public Entry {
            origin = origin == null ? DEFAULT_ORIGIN : origin;
        }

        public Entry(String ts, String actor, String op, String target) {
            this(ts, actor, op, target, null, null, null, null, null, null);
        }
    }

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
        java.util.Map<String, Object> fields;
        try {
            fields = FlatJson.parseObject(line);
        } catch (RuntimeException unparseable) {
            return Optional.empty();
        }
        Entry entry = new Entry(
                text(fields, "ts"), text(fields, "actor"), text(fields, "op"), text(fields, "target"),
                text(fields, "origin"), text(fields, "approval"), text(fields, "transaction"),
                text(fields, "program"), text(fields, "digest"),
                fields.get("programLength") instanceof Number length ? Integer.valueOf(length.intValue()) : null);
        return entry.op() == null ? Optional.empty() : Optional.of(entry);
    }

    private static String text(java.util.Map<String, Object> fields, String name) {
        return fields.get(name) instanceof String value ? value : null;
    }

    private static String quote(String raw) {
        return FlatJson.quote(raw);
    }
}
