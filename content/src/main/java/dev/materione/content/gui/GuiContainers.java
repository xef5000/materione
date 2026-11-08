package dev.materione.content.gui;

import dev.materione.api.gui.GuiContainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The container profiles (gui.md §12.4, §12.5.1): the one file {@code shared/gui/containers.json}, shipped in the jar
 * as {@code /gui/containers.json} and read here, by the studio ({@code webui/src/lib/gui/containers.ts}) and by the
 * golden that pins it. Nothing in the code is specific to one container: a chest is an entry like the hopper.
 *
 * <p>A profile file that does not read is a broken jar, not broken content, so it fails loudly on first use
 * (the same stance as {@link VanillaAscii}); the GUI pipeline never reaches it with a bad id.
 */
public final class GuiContainers {

    /** Where the profile file sits on the classpath. */
    public static final String SOURCE_RESOURCE = "/gui/containers.json";

    /** The only profile file version this reader understands. */
    static final int VERSION = 1;

    private static final List<GuiContainer> ALL;
    private static final Map<String, GuiContainer> BY_ID;

    static {
        ALL = load();
        var byId = new LinkedHashMap<String, GuiContainer>();
        for (GuiContainer container : ALL) {
            if (byId.put(container.id(), container) != null) {
                throw new IllegalStateException("duplicate container id " + container.id() + " in " + SOURCE_RESOURCE);
            }
        }
        BY_ID = Map.copyOf(byId);
    }

    private GuiContainers() {}

    /** Every container profile, in file order. */
    public static List<GuiContainer> all() {
        return ALL;
    }

    /** The profile named by a {@code container:} value, when this server supports it. */
    public static Optional<GuiContainer> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(BY_ID.get(id));
    }

    /** The supported ids, comma separated, for the diagnostic that names an unknown one. */
    public static String supportedIds() {
        var ids = new ArrayList<String>();
        ALL.forEach(container -> ids.add(container.id()));
        return String.join(", ", ids);
    }

    /** The profile file's text, exactly as shipped (the golden pins its digest). */
    public static String sourceText() {
        try (InputStream in = GuiContainers.class.getResourceAsStream(SOURCE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + SOURCE_RESOURCE + " (shared/gui/containers.json)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + SOURCE_RESOURCE, e);
        }
    }

    private static List<GuiContainer> load() {
        return parse(sourceText());
    }

    /**
     * Reads a profile file. Package-visible so the tests can read a mutated copy.
     *
     * @throws IllegalStateException when the text is not a version-1 profile file
     */
    static List<GuiContainer> parse(String text) {
        Object root = new Json(text).document();
        if (!(root instanceof Map<?, ?> document) || !(document.get("version") instanceof Long version)
                || version != VERSION) {
            throw new IllegalStateException(SOURCE_RESOURCE + " is not a version " + VERSION + " profile file");
        }
        if (!(document.get("containers") instanceof List<?> entries)) {
            throw new IllegalStateException(SOURCE_RESOURCE + " has no containers list");
        }
        var out = new ArrayList<GuiContainer>();
        for (Object entry : entries) {
            out.add(container(map(entry, "container")));
        }
        return List.copyOf(out);
    }

    private static GuiContainer container(Map<String, Object> m) {
        Map<String, Object> slots = map(m.get("slots"), "slots");
        Map<String, Object> inventory = map(m.get("inventory"), "inventory");
        Map<String, Object> title = map(m.get("title"), "title");
        Map<String, Object> label = map(m.get("inventoryLabel"), "inventoryLabel");
        boolean centred = Boolean.TRUE.equals(title.get("centred"));
        var underlay = new ArrayList<GuiContainer.Blit>();
        if (!(m.get("underlay") instanceof List<?> blits)) {
            throw new IllegalStateException("underlay must be a list");
        }
        for (Object blit : blits) {
            Map<String, Object> b = map(blit, "underlay entry");
            List<Integer> src = ints(b.get("src"), 4, "src");
            List<Integer> dst = ints(b.get("dst"), 2, "dst");
            underlay.add(new GuiContainer.Blit(string(b.get("sheet"), "sheet"),
                    src.get(0), src.get(1), src.get(2), src.get(3), dst.get(0), dst.get(1)));
        }
        Map<String, Object> open = map(m.get("open"), "open");
        GuiContainer.Open opening = open.containsKey("size")
                ? GuiContainer.Open.bySize(integer(open.get("size"), "open.size"))
                : GuiContainer.Open.byType(string(open.get("inventoryType"), "open.inventoryType"));
        return new GuiContainer(
                string(m.get("id"), "id"), string(m.get("label"), "label"), string(m.get("group"), "group"),
                integer(m.get("slotCount"), "slotCount"), integer(m.get("columns"), "columns"),
                integer(slots.get("x"), "slots.x"), integer(slots.get("y"), "slots.y"),
                integer(m.get("width"), "width"), integer(m.get("height"), "height"),
                integer(inventory.get("x"), "inventory.x"), integer(inventory.get("y"), "inventory.y"),
                integer(m.get("inventoryTop"), "inventoryTop"),
                centred, centred ? 0 : integer(title.get("x"), "title.x"), integer(title.get("y"), "title.y"),
                integer(label.get("x"), "inventoryLabel.x"), integer(label.get("y"), "inventoryLabel.y"),
                underlay, opening);
    }

    private static Map<String, Object> map(Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((key, entry) -> out.put(String.valueOf(key), entry));
            return out;
        }
        throw new IllegalStateException(what + " must be an object");
    }

    private static String string(Object value, String what) {
        if (value instanceof String text && !text.isEmpty()) {
            return text;
        }
        throw new IllegalStateException(what + " must be a non-empty string");
    }

    private static int integer(Object value, String what) {
        if (value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        throw new IllegalStateException(what + " must be a whole number");
    }

    private static List<Integer> ints(Object value, int count, String what) {
        if (!(value instanceof List<?> list) || list.size() != count) {
            throw new IllegalStateException(what + " must be a list of " + count + " numbers");
        }
        var out = new ArrayList<Integer>(count);
        for (Object entry : list) {
            out.add(integer(entry, what));
        }
        return out;
    }

    /**
     * A strict, minimal JSON reader for the profile file: objects, arrays, strings (with the usual escapes), whole
     * numbers, {@code true}, {@code false} and {@code null}. Anything else is an error; the file is ours, so there is
     * no leniency to offer. Whole numbers come back as {@link Long}.
     */
    private static final class Json {

        private final String text;
        private int at;

        Json(String text) {
            this.text = text;
        }

        Object document() {
            Object value = value();
            skipSpace();
            if (at != text.length()) {
                throw error("trailing text");
            }
            return value;
        }

        private Object value() {
            skipSpace();
            if (at >= text.length()) {
                throw error("unexpected end of file");
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> object() {
            var out = new LinkedHashMap<String, Object>();
            at++;
            skipSpace();
            if (peek('}')) {
                at++;
                return out;
            }
            while (true) {
                skipSpace();
                if (!peek('"')) {
                    throw error("expected a key");
                }
                String key = string();
                skipSpace();
                expect(':');
                if (out.put(key, value()) != null) {
                    throw error("duplicate key " + key);
                }
                skipSpace();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        private List<Object> array() {
            var out = new ArrayList<Object>();
            at++;
            skipSpace();
            if (peek(']')) {
                at++;
                return out;
            }
            while (true) {
                out.add(value());
                skipSpace();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect(']');
                return out;
            }
        }

        private String string() {
            at++;
            var out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    break;
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw error("short unicode escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw error("unsupported escape \\" + escape);
                }
            }
            throw error("unterminated string");
        }

        private Long number() {
            int start = at;
            if (text.charAt(at) == '-') {
                at++;
            }
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            try {
                return Long.parseLong(text.substring(start, at));
            } catch (NumberFormatException e) {
                throw error("not a whole number");
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw error("unexpected text");
            }
            at += word.length();
            return value;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private boolean peek(char c) {
            return at < text.length() && text.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            at++;
        }

        private IllegalStateException error(String what) {
            return new IllegalStateException(SOURCE_RESOURCE + " is not valid JSON at " + at + ": " + what);
        }
    }
}
