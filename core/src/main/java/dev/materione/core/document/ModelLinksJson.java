package dev.materione.core.document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for the model-relationship manifest
 * ({@code .materione/model-links.json}). Core is Gson-free by rule — Gson is
 * server-provided and this layer must stay embeddable — and the manifest is
 * one hand-shaped document, so a strict subset reader (objects, strings,
 * number/boolean/null literals) with a literal-preserving writer is all the
 * transaction needs. Output is byte-compatible with the Gson
 * pretty-printer that wrote this file historically (2-space indent,
 * insertion order, HTML-safe escapes), so an upgrade never diffs the whole
 * manifest.
 */
final class ModelLinksJson {

    /** The one validation message callers surface as {@code MTR-0035}. */
    static final String INVALID = "model relationship manifest is invalid";

    private ModelLinksJson() {}

    /** Parses one JSON object into ordered maps; anything else is invalid. */
    static Map<String, Object> parse(String raw) {
        Parser parser = new Parser(raw);
        Object value;
        try {
            parser.skipWhitespace();
            value = parseValue(parser, 0);
            parser.skipWhitespace();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(INVALID);
        }
        if (parser.pos != raw.length() || !(value instanceof Map)) {
            throw new IllegalArgumentException(INVALID);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    private static Object parseValue(Parser parser, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException(INVALID);
        }
        parser.skipWhitespace();
        char c = parser.peek();
        return switch (c) {
            case '{' -> parseObject(parser, depth);
            case '[' -> parseArray(parser, depth);
            case '"' -> parseString(parser);
            case 't' -> {
                parser.expect("true");
                yield Boolean.TRUE;
            }
            case 'f' -> {
                parser.expect("false");
                yield Boolean.FALSE;
            }
            case 'n' -> {
                parser.expect("null");
                yield null;
            }
            default -> parseNumber(parser);
        };
    }

    private static Map<String, Object> parseObject(Parser parser, int depth) {
        parser.take('{');
        Map<String, Object> object = new LinkedHashMap<>();
        parser.skipWhitespace();
        if (parser.peek() == '}') {
            parser.take('}');
            return object;
        }
        while (true) {
            parser.skipWhitespace();
            String key = parseString(parser);
            parser.skipWhitespace();
            parser.take(':');
            object.put(key, parseValue(parser, depth + 1));
            parser.skipWhitespace();
            char next = parser.takeAny();
            if (next == '}') {
                return object;
            }
            if (next != ',') {
                throw new IllegalArgumentException(INVALID);
            }
        }
    }

    private static String parseString(Parser parser) {
        parser.take('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = parser.takeAny();
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                char escape = parser.takeAny();
                out.append(switch (escape) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> parseUnicode(parser);
                    default -> throw new IllegalArgumentException(INVALID);
                });
            } else if (c < 0x20) {
                throw new IllegalArgumentException(INVALID);
            } else {
                out.append(c);
            }
        }
    }

    private static char parseUnicode(Parser parser) {
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char digit = parser.takeAny();
            int value = Character.digit(digit, 16);
            if (value < 0) {
                throw new IllegalArgumentException(INVALID);
            }
            code = (code << 4) | value;
        }
        return (char) code;
    }

    private static LiteralNumber parseNumber(Parser parser) {
        int start = parser.pos;
        if (parser.peek() == '-') {
            parser.pos++;
        }
        while (parser.pos < parser.raw.length()
                && "0123456789+-.eE".indexOf(parser.raw.charAt(parser.pos)) >= 0) {
            parser.pos++;
        }
        String literal = parser.raw.substring(start, parser.pos);
        if (literal.isEmpty() || literal.equals("-")) {
            throw new IllegalArgumentException(INVALID);
        }
        // Literal-preserving: the writer re-emits exactly what was read.
        return new LiteralNumber(literal);
    }

    // ------------------------------------------------------------------

    /** Renders one object as Gson pretty-printed JSON (2-space indent, trailing newline by caller). */
    static String serialize(Map<String, Object> value) {
        StringBuilder out = new StringBuilder();
        writeObject(out, value, 0);
        return out.toString();
    }

    private static void writeObject(StringBuilder out, Map<String, Object> object, int depth) {
        if (object.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int index = 0;
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            indent(out, depth + 1);
            writeString(out, entry.getKey());
            out.append(": ");
            writeValue(out, entry.getValue(), depth + 1);
            if (++index < object.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeValue(StringBuilder out, Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) map;
            writeObject(out, object, depth);
            return;
        }
        if (value instanceof List<?> list) {
            writeArray(out, list, depth);
            return;
        }
        if (value instanceof LiteralNumber number) {
            out.append(number.literal());
            return;
        }
        if (value instanceof Number number) {
            out.append(number);
            return;
        }
        if (value instanceof Boolean flag) {
            out.append(flag);
            return;
        }
        if (value == null) {
            out.append("null");
            return;
        }
        writeString(out, String.valueOf(value));
    }

    private static void writeArray(StringBuilder out, List<?> items, int depth) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            indent(out, depth + 1);
            writeValue(out, items.get(i), depth + 1);
            if (i < items.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, depth);
        out.append(']');
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                // Gson's default writer is HTML-safe: these never ride a URL
                // or script tag unescaped.
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case '=' -> out.append("\\u003d");
                case '\'' -> out.append("\\u0027");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    // ------------------------------------------------------------------

    /** A number kept as its source text so re-serialization is byte-identical. */
    private record LiteralNumber(String literal) {
        @Override public String toString() { return literal(); }
    }

    private static final class Parser {
        private final String raw;
        private int pos;

        Parser(String raw) {
            this.raw = raw;
        }

        private void skipWhitespace() {
            while (pos < raw.length() && Character.isWhitespace(raw.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= raw.length()) {
                throw new IllegalArgumentException(INVALID);
            }
            return raw.charAt(pos);
        }

        private char takeAny() {
            if (pos >= raw.length()) {
                throw new IllegalArgumentException(INVALID);
            }
            return raw.charAt(pos++);
        }

        private void take(char expected) {
            char c = takeAny();
            if (c != expected) {
                throw new IllegalArgumentException(INVALID);
            }
        }

        private void expect(String word) {
            if (!raw.startsWith(word, pos)) {
                throw new IllegalArgumentException(INVALID);
            }
            pos += word.length();
            if (pos > raw.length()) {
                throw new IllegalArgumentException(INVALID);
            }
        }
    }

    // ------------------------------------------------------------------

    private static List<Object> parseArray(Parser parser, int depth) {
        parser.take('[');
        List<Object> items = new ArrayList<>();
        parser.skipWhitespace();
        if (parser.peek() == ']') {
            parser.take(']');
            return items;
        }
        while (true) {
            items.add(parseValue(parser, depth + 1));
            parser.skipWhitespace();
            char next = parser.takeAny();
            if (next == ']') {
                return items;
            }
            if (next != ',') {
                throw new IllegalArgumentException(INVALID);
            }
        }
    }
}
