package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The live content as a tree of packs, folders and entries: what a browser walks (runtime-systems §8.3). It is a
 * view the pipeline builds in the same shadow as the registries it describes (plugin.md §4.5), so it swaps atomically
 * with them and always names only what is live.
 *
 * <p>The tree is the files' own: a pack is a node, and the folders under a content directory ({@code items/gems/},
 * {@code blocks/gems/}) are its folders. Content directories merge, so {@code items/gems/ruby.yml} and
 * {@code blocks/gems/ruby_ore.yml} sit in one folder {@code gems}. Authors make "categories" by making folders;
 * nothing is labelled twice (owner decision on #284).
 *
 * <p>An entry is something a player can take: a live item, or a block or furniture that has no item resource of its
 * own (the runtime made its placement item). A block or furniture whose placement item is a real item is listed as that
 * item, in the item's own folder, so a thing is never listed twice. In every case {@link Entry#id()} is the id of the
 * item that is handed out.
 *
 * <p>A location is a path of names joined by {@code /}: {@code ""} is the root (the packs), {@code shop} is a pack,
 * {@code shop/gems/raw} a folder in it. Pure and immutable; no server types.
 */
public final class RuntimeContentIndex {

    /** The view key the pipeline registers this under and the browsing list source reads it from. */
    public static final Class<RuntimeContentIndex> KEY = RuntimeContentIndex.class;

    /** What an entry was declared as. */
    public enum Type { ITEM, BLOCK, FURNITURE }

    /**
     * One takeable thing.
     *
     * @param id the id of the item that is handed out; the entry's pack is its namespace
     * @param type what the file declared: an item, or a block or furniture whose placement item is generated
     * @param name the display name as plain text (formatting removed); the id's value, humanised, when it has none
     * @param label the display name as authored (MiniMessage), so a window can show the item's name as it really
     *     looks; equals {@code name} when the item has none
     * @param folders the folders under the content directory the declaring file sits in, outermost first
     */
    public record Entry(NamespacedId id, Type type, String name, String label, List<String> folders) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(label, "label");
            folders = List.copyOf(folders);
        }

        /** An entry whose label is its plain name (an item with no formatting in its name). */
        public Entry(NamespacedId id, Type type, String name, List<String> folders) {
            this(id, type, name, name, folders);
        }

        /** The pack, which is the namespace. */
        public String pack() {
            return id.namespace();
        }

        /** The location of the folder this entry is in: the pack, then its folders. */
        public String location() {
            return folders.isEmpty() ? pack() : pack() + "/" + String.join("/", folders);
        }

        /** The lower-cased text a search matches against: the id and the name. */
        public String searchKey() {
            return (id + " " + name).toLowerCase(Locale.ROOT);
        }
    }

    /** What a {@link Node} is. */
    public enum Kind { PACK, FOLDER, ENTRY }

    /**
     * One row of a location: a pack, a folder or an entry.
     *
     * @param kind which
     * @param name what it is called: the pack, the folder, or the entry's display name
     * @param path the location it opens when it is a pack or a folder; empty for an entry
     * @param count how many entries are under a pack or a folder (all depths); 1 for an entry
     * @param entry the entry, or null for a pack or a folder
     */
    public record Node(Kind kind, String name, String path, int count, Entry entry) {}

    private static final RuntimeContentIndex EMPTY = new RuntimeContentIndex(List.of());

    private final List<Entry> entries;

    private RuntimeContentIndex(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** No content. */
    public static RuntimeContentIndex empty() {
        return EMPTY;
    }

    /** An index over these entries, in any order. */
    public static RuntimeContentIndex of(List<Entry> entries) {
        var sorted = new ArrayList<>(entries);
        sorted.sort(ENTRY_ORDER);
        return new RuntimeContentIndex(sorted);
    }

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparing((Entry entry) -> entry.name().toLowerCase(Locale.ROOT))
            .thenComparing(entry -> entry.id().toString());

    /** Every entry, sorted by name (case-insensitive), then id. */
    public List<Entry> entries() {
        return entries;
    }

    /** The number of entries. */
    public int size() {
        return entries.size();
    }

    /**
     * What is directly at {@code location}: at the root, the packs that hold an entry; inside a pack or a folder,
     * its sub-folders first (by name, case-insensitive), then its entries (by name, then id). A location that names
     * nothing live is empty.
     */
    public List<Node> children(String location) {
        List<String> path = split(location);
        var folders = new LinkedHashMap<String, Integer>();
        var direct = new ArrayList<Entry>();
        for (Entry entry : entries) {
            String child = childOf(entry, path);
            if (child == null) {
                continue;
            }
            if (child.isEmpty()) {
                direct.add(entry);
            } else {
                folders.merge(child, 1, Integer::sum);
            }
        }
        var out = new ArrayList<Node>();
        folders.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())))
                .forEach(folder -> out.add(new Node(
                        path.isEmpty() ? Kind.PACK : Kind.FOLDER, folder.getKey(),
                        path.isEmpty() ? folder.getKey() : location + "/" + folder.getKey(),
                        folder.getValue(), null)));
        for (Entry entry : direct) {
            out.add(new Node(Kind.ENTRY, entry.name(), "", 1, entry));
        }
        return out;
    }

    /**
     * Where {@code entry} sits relative to {@code path}: null when it is not under it, the empty string when it is
     * directly in it, otherwise the name of the pack (at the root) or of the sub-folder it is under.
     */
    private static String childOf(Entry entry, List<String> path) {
        if (path.isEmpty()) {
            return entry.pack();
        }
        if (!path.get(0).equals(entry.pack())) {
            return null;
        }
        List<String> folders = entry.folders();
        int depth = path.size() - 1;
        if (folders.size() < depth) {
            return null;
        }
        for (int i = 0; i < depth; i++) {
            if (!folders.get(i).equals(path.get(i + 1))) {
                return null;
            }
        }
        return folders.size() == depth ? "" : folders.get(depth);
    }

    /** The parent of a location: the folder or pack above it, {@code ""} for a pack. */
    public static String parent(String location) {
        int slash = location == null ? -1 : location.lastIndexOf('/');
        return slash < 0 ? "" : location.substring(0, slash);
    }

    /** The names of a location, outermost first; the root has none. */
    public static List<String> split(String location) {
        if (location == null || location.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (String segment : location.split("/")) {
            if (!segment.isEmpty()) {
                out.add(segment);
            }
        }
        return out;
    }

    /**
     * A display name as plain text: formatting tags ({@code <red>}) and legacy colour codes ({@code &c}) removed. A
     * name that is absent or blank falls back to the id's value, humanised ({@code amethyst_lamp} to
     * {@code Amethyst Lamp}).
     */
    public static String plainName(String displayName, NamespacedId id) {
        if (displayName != null) {
            String plain = displayName.replaceAll("<[^>]*>", "")
                    .replaceAll("[&§][0-9a-fk-orA-FK-OR]", "")
                    .strip();
            if (!plain.isEmpty()) {
                return plain;
            }
        }
        return humanize(id.value());
    }

    /** {@code amethyst_lamp} to {@code Amethyst Lamp}; a folder in the id ({@code gui/main/sword}) keeps its last name. */
    static String humanize(String value) {
        int slash = value.lastIndexOf('/');
        String last = slash >= 0 ? value.substring(slash + 1) : value;
        var out = new StringBuilder();
        for (String word : last.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.isEmpty() ? value : out.toString();
    }
}
