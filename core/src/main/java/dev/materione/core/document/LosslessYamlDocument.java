package dev.materione.core.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One pack document with its formatting context (master §5.6). Immutable
 * except through {@link #withScalar}, which returns an edited copy — the
 * editor and the pipeline never hold a half-edited document.
 *
 * <p>Round-trip invariant: {@code LosslessYaml.parse(raw).save()} equals
 * {@code raw} exactly. Edits rewrite only the line of the touched key (or
 * insert one new line); everything else — comments, blank lines, unknown
 * fields — is carried over verbatim.
 */
public final class LosslessYamlDocument {

    /** key line: indent, key (plain or quoted), optional value / inline comment. */
    private static final Pattern KEY_LINE =
            Pattern.compile("^(\\s*)(-\\s+)?([A-Za-z_][\\w .\\-/]*?)\\s*:(?:[ \\t]+(.*))?$");

    private static final Pattern INDENT = Pattern.compile("^\\s*");
    private static final Pattern SAFE_PLAIN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_ ./+\\-]*");

    private final String raw;
    private final Map<String, Object> data;
    private final String parseError;
    /**
     * For the view of a flat file (see {@link #asSingleResource}): the file's own document, whose text the view saves
     * and whose top level an edit addressed to the view lands on. Null for every other document.
     */
    private final LosslessYamlDocument flatFile;
    /** The resource id the view reads the flat file as; null for every other document. */
    private final String flatKey;

    LosslessYamlDocument(String raw, Map<String, Object> data, String parseError) {
        this(raw, data, parseError, null, null);
    }

    private LosslessYamlDocument(
            String raw, Map<String, Object> data, String parseError, LosslessYamlDocument flatFile, String flatKey) {
        this.raw = Objects.requireNonNull(raw, "raw");
        // a top-level key with no value (`sword:` with nothing under it) is a null value, not a reason to refuse the file
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        this.parseError = parseError;
        this.flatFile = flatFile;
        this.flatKey = flatKey;
    }

    /**
     * The read view of a flat document (#469): the document's own top-level fields, read as the one resource {@code key}
     * of the file, so {@code data()} is {@code {key: fields}} exactly as a keyed document of that resource reads. The
     * text is still the file's: {@link #save()} returns it, never a keyed rewrite.
     *
     * <p>An edit addressed to the view is addressed the way a keyed document's is, {@code [key, field...]}, and lands
     * on the file's top level as {@code [field...]}: one write path for both shapes, so the file keeps its shape and
     * every comment and unknown field byte for byte (master section 5.6). The result is a view again. Three edits
     * are refused with {@link UnsupportedOperationException}: one not addressed to {@code key}; one addressed to the
     * whole resource (the resource is the file, so it is deleted as a file); and one that would put a mapping at the
     * top level, because the file would then read as keyed and its fields would stop being fields. A document that
     * did not parse has no view.
     *
     * @param key the resource id the file's name gives (the file name without its {@code .yml})
     */
    public LosslessYamlDocument asSingleResource(String key) {
        Objects.requireNonNull(key, "key");
        if (parseError != null) {
            return this;
        }
        LosslessYamlDocument file = flatFile != null ? flatFile : this;
        return new LosslessYamlDocument(file.raw, Map.<String, Object>of(key, file.data), null, file, key);
    }

    /**
     * Whether a top-level value is a mapping: the test that makes a file keyed (each such key is a resource id), as
     * {@code PackResources} reads a file's shape. A flat file has none.
     */
    public boolean hasTopLevelMapping() {
        return data.values().stream().anyMatch(Map.class::isInstance);
    }

    /** Non-null when the file failed to parse (already reflected in the data view being empty). */
    public Optional<String> parseError() {
        return Optional.ofNullable(parseError);
    }

    /** The data view (never null; empty when unparsable). Unknown fields included. */
    public Map<String, Object> data() {
        return data;
    }

    /** Byte-identical re-emission of what was loaded (or the edited text after {@link #withScalar}). */
    public String save() {
        return raw;
    }

    /** Reads a nested scalar from the data view; empty when absent or not a scalar. */
    public Optional<Object> scalar(String... path) {
        Object current = null;
        Map<String, Object> level = data;
        for (int i = 0; i < path.length; i++) {
            current = level.get(path[i]);
            if (current == null) {
                return Optional.empty();
            }
            if (i < path.length - 1) {
                if (current instanceof Map<?, ?> next) {
                    @SuppressWarnings("unchecked")
                    var nextMap = (Map<String, Object>) next;
                    level = nextMap;
                } else {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(current);
    }

    /**
     * Returns a copy with the node at {@code path} set to {@code value}.
     * Scalars rewrite (or insert) exactly one line — a trailing inline
     * comment is preserved. Block sequences ({@code List} values) rewrite
     * the whole owned range as {@code key:} plus {@code - item} lines, so
     * forms can edit fields like lore without re-serializing anything else.
     * Keys inside flow style or sequences are not editable —
     * {@link UnsupportedOperationException} says so instead of guessing.
     */
    public LosslessYamlDocument withScalar(Object value, String... path) {
        if (flatFile != null) {
            return onFlatFile(path, rest -> flatFile.withScalar(value, rest));
        }
        if (path.length == 0) {
            throw new IllegalArgumentException("empty path");
        }
        List<String> lines = splitLines(raw);
        Node root = buildTree(lines);
        String[] parentPath = java.util.Arrays.copyOf(path, path.length - 1);
        String key = path[path.length - 1];
        String newline = dominantTerminator(raw);

        Node target = find(root, List.of(path));
        if (target != null) {
            String keyLine = rewriteLine(lines.get(target.lineIndex), tailOf(value));
            if (!(value instanceof java.util.List<?> items)) {
                lines.set(target.lineIndex, keyLine);
                return new LosslessYamlDocument(String.join("", lines), data, parseError).reparsed();
            }
            List<String> block = new ArrayList<>();
            block.add(keyLine);
            int indent = target.childIndent(lines);
            for (Object item : items) {
                block.add(" ".repeat(indent) + "- " + render(item) + newline);
            }
            // Replace the key line plus its item lines; trailing comments or
            // blanks after the last element belong to the document, not the
            // old value, so they survive.
            int lastItem = target.lineIndex;
            for (int i = target.lastLineIndex; i > target.lineIndex; i--) {
                if (lines.get(i).stripLeading().startsWith("-")) {
                    lastItem = i;
                    break;
                }
            }
            lines.subList(target.lineIndex, lastItem + 1).clear();
            lines.addAll(target.lineIndex, block);
            return new LosslessYamlDocument(String.join("", lines), data, parseError).reparsed();
        }

        Node parent = find(root, List.of(parentPath));
        int insertAt;
        int indent;
        if (parent != null) {
            insertAt = parent.lastLineIndex + 1;
            indent = parent.childIndent(lines);
        } else if (path.length == 1) {
            insertAt = lastRootLine(root, lines);
            indent = 0;
        } else {
            throw new UnsupportedOperationException(
                    "cannot insert '" + String.join(".", path) + "': parent mapping not found");
        }
        List<String> inserted = new ArrayList<>();
        String tail = tailOf(value);
        inserted.add(" ".repeat(indent) + key + (tail.isEmpty() ? ":" : ": " + tail) + newline);
        if (value instanceof java.util.List<?> items && !items.isEmpty()) {
            for (Object item : items) {
                inserted.add(" ".repeat(indent + 2) + "- " + render(item) + newline);
            }
        }
        lines.addAll(insertAt, inserted);
        return new LosslessYamlDocument(String.join("", lines), data, parseError).reparsed();
    }

    /**
     * Returns a copy with the node at {@code path} set to a structured value —
     * a mapping, or a sequence whose items are mappings. Only the field's own
     * block is rewritten; every other line, comment and unknown field in the
     * document is carried over verbatim, so a studio can edit furniture
     * hitboxes or recipe ingredients without re-serializing the file (§5.6).
     *
     * <p>Scalars and sequences of scalars are delegated to {@link #withScalar}
     * so simple fields keep producing one-line diffs.
     */
    public LosslessYamlDocument withNode(Object value, String... path) {
        if (flatFile != null) {
            return onFlatFile(path, rest -> flatFile.withNode(value, rest));
        }
        if (path.length == 0) {
            throw new IllegalArgumentException("empty path");
        }
        if (isFlat(value)) {
            return withScalar(value, path);
        }
        List<String> lines = splitLines(raw);
        Node root = buildTree(lines);
        String newline = dominantTerminator(raw);
        String key = path[path.length - 1];

        Node target = find(root, List.of(path));
        int indent;
        int insertAt;
        int removeThrough = -1;
        if (target != null) {
            indent = target.indent;
            insertAt = target.lineIndex;
            removeThrough = lastContentLine(lines, target);
        } else {
            Node parent = find(root, List.of(java.util.Arrays.copyOf(path, path.length - 1)));
            if (parent != null) {
                insertAt = parent.lastLineIndex + 1;
                indent = parent.childIndent(lines);
            } else if (path.length == 1) {
                insertAt = lastRootLine(root, lines);
                indent = 0;
            } else {
                throw new UnsupportedOperationException(
                        "cannot insert '" + String.join(".", path) + "': parent mapping not found");
            }
        }

        List<String> block = new ArrayList<>();
        block.add(" ".repeat(indent) + key + ":" + emptyMarker(value) + newline);
        block.addAll(renderBody(value, indent + 2, newline));
        if (removeThrough >= 0) {
            lines.subList(insertAt, removeThrough + 1).clear();
        }
        lines.addAll(insertAt, block);
        return new LosslessYamlDocument(String.join("", lines), data, parseError).reparsed();
    }

    /**
     * Returns a copy without the key at {@code path} — the explicit "reset to
     * inherited value" edit. The key line and the lines its value owns are
     * removed; comments and blank lines after the value belong to the
     * document and stay, as in {@link #withNode}. An absent key returns this
     * document unchanged. When the key was its parent's only child, the
     * parent line gains {@code {}} so it stays a mapping instead of becoming
     * null. Keys in flow style or that open a sequence item are refused with
     * {@link UnsupportedOperationException} rather than guessed at.
     */
    public LosslessYamlDocument withoutNode(String... path) {
        if (flatFile != null) {
            return onFlatFile(path, flatFile::withoutNode);
        }
        if (path.length == 0) {
            throw new IllegalArgumentException("empty path");
        }
        List<String> lines = splitLines(raw);
        Node root = buildTree(lines);
        Node target = find(root, List.of(path));
        if (target == null) {
            if (present(path)) {
                throw new UnsupportedOperationException(
                        "cannot remove '" + String.join(".", path) + "': it is not a block mapping key");
            }
            return this;
        }
        Matcher keyLine = KEY_LINE.matcher(lines.get(target.lineIndex).replaceFirst("\\r?\\n$", ""));
        if (keyLine.matches() && keyLine.group(2) != null) {
            throw new UnsupportedOperationException(
                    "cannot remove '" + String.join(".", path) + "': it opens a sequence item");
        }
        int last = lastContentLine(lines, target);
        Node parent = path.length > 1
                ? find(root, List.of(java.util.Arrays.copyOf(path, path.length - 1))) : null;
        lines.subList(target.lineIndex, last + 1).clear();
        if (parent != null && parent.children.size() == 1) {
            lines.set(parent.lineIndex, rewriteLine(lines.get(parent.lineIndex), "{}"));
        }
        return new LosslessYamlDocument(String.join("", lines), data, parseError).reparsed();
    }

    /**
     * True when {@code path} may exist where the line tree cannot reach it:
     * present in the data view, or behind a sequence the data view cannot
     * address by key — removal there is refused rather than reported done.
     */
    private boolean present(String... path) {
        Object level = data;
        for (String key : path) {
            if (level instanceof List<?>) {
                return true;
            }
            if (!(level instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return false;
            }
            level = map.get(key);
        }
        return true;
    }

    /** Scalars and sequences of scalars stay on the one-line-diff path. */
    private static boolean isFlat(Object value) {
        if (value instanceof Map<?, ?>) {
            return false;
        }
        if (value instanceof List<?> items) {
            return items.stream().noneMatch(item -> item instanceof Map || item instanceof List);
        }
        return true;
    }

    /** The last line the old value actually owned — trailing blanks/comments survive. */
    private static int lastContentLine(List<String> lines, Node target) {
        for (int i = target.lastLineIndex; i > target.lineIndex; i--) {
            if (!isBlankOrComment(lines.get(i))) {
                return i;
            }
        }
        return target.lineIndex;
    }

    private static String emptyMarker(Object value) {
        if (value instanceof List<?> items && items.isEmpty()) {
            return " []";
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return " {}";
        }
        return "";
    }

    private static List<String> renderBody(Object value, int indent, String newline) {
        List<String> out = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                out.addAll(renderEntry(
                        String.valueOf(entry.getKey()), entry.getValue(), indent, newline));
            }
            return out;
        }
        for (Object item : (List<?>) value) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                // "- first: value" then the remaining keys aligned under it.
                List<String> body = renderBody(map, indent + 2, newline);
                out.add(" ".repeat(indent) + "- " + body.get(0).stripLeading());
                out.addAll(body.subList(1, body.size()));
            } else if (item instanceof List<?> nested) {
                out.add(" ".repeat(indent) + "- " + flow(nested) + newline);
            } else {
                out.add(" ".repeat(indent) + "- " + render(item) + newline);
            }
        }
        return out;
    }

    private static List<String> renderEntry(String key, Object value, int indent, String newline) {
        String pad = " ".repeat(indent);
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return List.of(pad + key + ": {}" + newline);
            }
            List<String> out = new ArrayList<>();
            out.add(pad + key + ":" + newline);
            out.addAll(renderBody(map, indent + 2, newline));
            return out;
        }
        if (value instanceof List<?> items) {
            if (items.isEmpty()) {
                return List.of(pad + key + ": []" + newline);
            }
            if (isFlat(items)) {
                // Coordinates read as coordinates: offset: [0, 0.5, 0].
                return List.of(pad + key + ": " + flow(items) + newline);
            }
            List<String> out = new ArrayList<>();
            out.add(pad + key + ":" + newline);
            out.addAll(renderBody(items, indent + 2, newline));
            return out;
        }
        return List.of(pad + key + ": " + render(value) + newline);
    }

    private static String flow(List<?> items) {
        List<String> rendered = new ArrayList<>();
        for (Object item : items) {
            rendered.add(render(item));
        }
        return "[" + String.join(", ", rendered) + "]";
    }

    /** Value part of the key line: scalars inline, sequences as `key:` / `key: []`. */
    private static String tailOf(Object value) {
        if (value instanceof java.util.List<?> items) {
            return items.isEmpty() ? "[]" : "";
        }
        return render(value);
    }

    /**
     * An edit addressed to the view of a flat file, made on the file itself: the path's first segment is the resource
     * id the view reads the file as, and the rest is the field's path inside the file.
     */
    private LosslessYamlDocument onFlatFile(
            String[] path, java.util.function.Function<String[], LosslessYamlDocument> edit) {
        if (path.length == 0) {
            throw new IllegalArgumentException("empty path");
        }
        if (!flatKey.equals(path[0])) {
            throw new UnsupportedOperationException("this file holds one resource, '" + flatKey
                    + "', whose fields are at its top level; it has no resource '" + path[0] + "'");
        }
        if (path.length == 1) {
            throw new UnsupportedOperationException("'" + flatKey + "' is the whole file; edit one of its fields, "
                    + "or delete the file to remove the resource");
        }
        LosslessYamlDocument edited = edit.apply(java.util.Arrays.copyOfRange(path, 1, path.length));
        if (edited.hasTopLevelMapping()) {
            throw new UnsupportedOperationException("'" + String.join(".", path) + "' would put a mapping at the "
                    + "top level of a file that holds one resource as its fields, so the file would read as keyed; "
                    + "key the resource by its id inside the file first");
        }
        return edited.asSingleResource(flatKey);
    }

    /** Re-derives the data view after an edit (formatting is already correct). */
    private LosslessYamlDocument reparsed() {
        return LosslessYaml.parse(save());
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        String text = value.toString();
        if (SAFE_PLAIN.matcher(text).matches() && !isReservedWord(text)) {
            return text;
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isReservedWord(String text) {
        return text.equals("true") || text.equals("false") || text.equals("null")
                || text.equals("yes") || text.equals("no") || text.equals("on") || text.equals("off")
                || text.matches("-?\\d+.*");
    }

    /** Replaces the value part of a key line, preserving indent, key and trailing comment. */
    private static String rewriteLine(String line, String rendered) {
        String body = line.replaceFirst("\\r?\\n$", "");
        Matcher terminator = Pattern.compile("(\\r?\\n)$").matcher(line);
        String eol = terminator.find() ? terminator.group(1) : "";
        Matcher m = KEY_LINE.matcher(body);
        if (!m.matches()) {
            throw new UnsupportedOperationException("line is not an editable key line: " + body);
        }
        String indent = m.group(1);
        String dash = m.group(2) == null ? "" : m.group(2);
        String key = m.group(3);
        String rest = m.group(4) == null ? "" : m.group(4);
        // split a trailing " #comment" off the old value
        int commentAt = rest.indexOf(" #");
        String comment = commentAt >= 0 ? rest.substring(commentAt) : "";
        String sep = rendered.isEmpty() && comment.isEmpty() ? ":" : ": ";
        return indent + dash + key + sep + rendered
                + (comment.isEmpty() ? "" : " " + comment) + eol;
    }

    private static int lastRootLine(Node root, List<String> lines) {
        if (root.children.isEmpty()) {
            return lines.isEmpty() ? 0 : lines.size();
        }
        return root.children.get(root.children.size() - 1).lastLineIndex + 1;
    }

    private static String dominantTerminator(String raw) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < raw.length() - 1; i++) {
            if (raw.charAt(i) == '\r' && raw.charAt(i + 1) == '\n') {
                crlf++;
            } else if (raw.charAt(i) == '\n') {
                lf++;
            }
        }
        return crlf > lf ? "\r\n" : "\n";
    }

    private static List<String> splitLines(String raw) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            current.append(c);
            if (c == '\n') {
                lines.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static int indentOf(String line) {
        var matcher = INDENT.matcher(line);
        matcher.find();
        return matcher.end();
    }

    private static boolean isKeyLine(String line) {
        String body = line.replaceFirst("\\r?\\n$", "");
        return KEY_LINE.matcher(body).matches();
    }

    private static boolean isBlankOrComment(String line) {
        String body = line.replaceFirst("\\r?\\n$", "").trim();
        return body.isEmpty() || body.startsWith("#");
    }

    // --- line tree ---------------------------------------------------------

    private static final class Node {
        final String key;
        final int lineIndex;
        final int indent;
        final List<Node> children = new ArrayList<>();
        int lastLineIndex;

        Node(String key, int lineIndex, int indent) {
            this.key = key;
            this.lineIndex = lineIndex;
            this.indent = indent;
            this.lastLineIndex = lineIndex;
        }

        int childIndent(List<String> lines) {
            for (Node child : children) {
                return child.indent;
            }
            for (int i = lineIndex + 1; i < lines.size(); i++) {
                if (!isBlankOrComment(lines.get(i))) {
                    int ind = indentOf(lines.get(i));
                    if (ind > indent) {
                        return ind;
                    }
                    break;
                }
            }
            return indent + 2;
        }
    }

    private static Node buildTree(List<String> lines) {
        Node root = new Node("", -1, -1);
        List<Node> stack = new ArrayList<>();
        stack.add(root);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isKeyLine(line)) {
                continue;
            }
            int indent = indentOf(line);
            Matcher m = KEY_LINE.matcher(line.replaceFirst("\\r?\\n$", ""));
            m.matches();
            Node node = new Node(m.group(3), i, indent);
            while (stack.size() > 1 && stack.get(stack.size() - 1).indent >= indent) {
                stack.remove(stack.size() - 1);
            }
            stack.get(stack.size() - 1).children.add(node);
            stack.add(node);
        }
        // assign lastLineIndex: each node owns lines until the next key line
        // at indent <= its own.
        assignLastLines(root, lines);
        return root;
    }

    private static void assignLastLines(Node node, List<String> lines) {
        for (Node child : node.children) {
            child.lastLineIndex = child.lineIndex;
            assignLastLines(child, lines);
            for (int i = child.lineIndex + 1; i < lines.size(); i++) {
                if (isKeyLine(lines.get(i)) && indentOf(lines.get(i)) <= child.indent) {
                    break;
                }
                child.lastLineIndex = i;
            }
            for (Node grandChild : child.children) {
                child.lastLineIndex = Math.max(child.lastLineIndex, grandChild.lastLineIndex);
            }
        }
    }

    private static Node find(Node root, List<String> path) {
        Node current = root;
        for (String segment : path) {
            Node next = null;
            for (Node child : current.children) {
                if (child.key.equals(segment)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current == root ? null : current;
    }
}
