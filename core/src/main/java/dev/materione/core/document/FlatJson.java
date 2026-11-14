package dev.materione.core.document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one-line, flat JSON the audit log and the transaction journal write: an object of strings, integers,
 * booleans and null. Hand-rolled because {@code core} stays embeddable without server-provided libraries, and
 * a scanner rather than a regular expression because a quoted value can be a whole program or document
 * (a repeated-alternation pattern overflows the stack on thousands of characters).
 */
public final class FlatJson {

    private FlatJson() {}

    /**
     * Parses one flat object. String values are unescaped; integers are {@link Long}; {@code null} is a null value.
     *
     * @throws IllegalArgumentException when the line is not a flat object
     */
    public static Map<String, Object> parseObject(String line) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            int[] at = {0};
            skip(line, at);
            expect(line, at, '{');
            skip(line, at);
            if (line.charAt(at[0]) == '}') {
                return out;
            }
            while (true) {
                skip(line, at);
                String key = string(line, at);
                skip(line, at);
                expect(line, at, ':');
                skip(line, at);
                out.put(key, value(line, at));
                skip(line, at);
                char next = line.charAt(at[0]++);
                if (next == '}') {
                    return out;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at " + (at[0] - 1));
                }
            }
        } catch (StringIndexOutOfBoundsException truncated) {
            throw new IllegalArgumentException("the line ends inside an object");
        }
    }

    private static Object value(String line, int[] at) {
        char c = line.charAt(at[0]);
        if (c == '"') {
            return string(line, at);
        }
        if (line.startsWith("null", at[0])) {
            at[0] += 4;
            return null;
        }
        if (line.startsWith("true", at[0])) {
            at[0] += 4;
            return Boolean.TRUE;
        }
        if (line.startsWith("false", at[0])) {
            at[0] += 5;
            return Boolean.FALSE;
        }
        int start = at[0];
        while (at[0] < line.length() && (Character.isDigit(line.charAt(at[0])) || line.charAt(at[0]) == '-')) {
            at[0]++;
        }
        return Long.parseLong(line.substring(start, at[0]));
    }

    private static String string(String line, int[] at) {
        expect(line, at, '"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = line.charAt(at[0]++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = line.charAt(at[0]++);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(line.substring(at[0], at[0] + 4), 16));
                    at[0] += 4;
                }
                default -> out.append(escaped);
            }
        }
    }

    private static void skip(String line, int[] at) {
        while (at[0] < line.length() && line.charAt(at[0]) == ' ') {
            at[0]++;
        }
    }

    private static void expect(String line, int[] at, char wanted) {
        if (line.charAt(at[0]++) != wanted) {
            throw new IllegalArgumentException("expected '" + wanted + "' at " + (at[0] - 1));
        }
    }

    /** JSON string quoting that keeps a document's text on one line and round-trips it exactly. */
    public static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
