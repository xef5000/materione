package dev.materione.core.document;

import java.util.HashMap;
import java.util.Map;

/**
 * Flat one-line JSON codec for revision-journal entries. Hand-rolled because
 * {@code core} must stay embeddable without server-provided libraries (the
 * same reason the lossless YAML layer loads SnakeYAML reflectively); the
 * shape is fixed and flat, so five regexes beat a dependency:
 * {@code {"rev":7,"ts":169…,"actor":"…","action":"…","sha":"…"|"null"}}.
 */
final class RevisionJson {

    private RevisionJson() {}

    static String format(DocumentStore.Revision revision) {
        return "{\"rev\":" + revision.rev()
                + ",\"ts\":" + revision.timestampMs()
                + ",\"actor\":" + quote(revision.actor())
                + ",\"action\":" + quote(revision.action())
                + ",\"sha\":" + (revision.sha256() == null ? "null" : quote(revision.sha256()))
                + "}";
    }

    /** Returns raw field text keyed by name, or null when the line is not parseable. */
    static Map<String, String> parse(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        if (!field(trimmed, "rev", fields) || !field(trimmed, "ts", fields)
                || !field(trimmed, "actor", fields) || !field(trimmed, "action", fields)
                || !field(trimmed, "sha", fields)) {
            return null;
        }
        return fields;
    }

    private static boolean field(String json, String name, Map<String, String> into) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + name + "\":(\"(?:[^\"\\\\]|\\\\.)*\"|-?[0-9]+|null)")
                .matcher(json);
        if (!matcher.find()) {
            return false;
        }
        into.put(name, matcher.group(1));
        return true;
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
