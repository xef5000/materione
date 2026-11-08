package dev.materione.plugin.gui.list;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.ListSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.runtime.RuntimeContentIndex;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.logic.support.ItemCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.bukkit.Material;

/**
 * {@code content}: the live content as a tree of packs, folders and entries (runtime-systems §8.3, logic.md §6.7). The
 * window shows one location of it at a time. The location is a GUI argument ({@code arg}, {@code path} by default):
 * empty is the root, which lists the packs; {@code shop} lists that pack's folders and entries; {@code shop/gems} a
 * folder in it. A click on a pack or a folder opens the same GUI again with that row's {@link #PATH path}, so the
 * back stack is the way up and every step keeps the page it was left on.
 *
 * <p>The tree is the pack's own folder tree (master §7.2): folders under {@code items/}, {@code blocks/} and
 * {@code furnitures/} merge, and an author makes a category by making a folder. Nothing is labelled twice.
 *
 * <p>While the session variable named by {@code search} holds text, the source lists every entry of every pack, flat:
 * a search finds an entry wherever it is filed, and the list's own {@code search:} key then keeps the matches (it
 * matches each word of the text against the {@code search} field). The source decides the scope; the list decides
 * which entries match. With no {@code search} parameter the location is always the tree.
 *
 * <p>The entries are read from the index of the same shadow as the live items (a view swapped with them), on the
 * viewer's thread, with no per-entry server work other than the item's stack size.
 */
public final class ContentTreeSource implements ListSource<ContentTreeSource.Params> {

    /** The parsed parameters: the argument that carries the location and the session variable that carries a search. */
    public record Params(String arg, String search) {}

    /** The fallback stack size of an item whose material is unknown or cannot say. */
    static final int DEFAULT_STACK = 64;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");

    private final ItemCatalog items;
    private final ToIntFunction<String> stackSizes;

    /** @param items the live registries the index is read from, each time the source reads */
    public ContentTreeSource(ItemCatalog items) {
        this(items, ContentTreeSource::materialStackSize);
    }

    /**
     * @param items the live registries the index is read from, each time the source reads
     * @param stackSizes the full stack of an item's base material, by its name; a seam, so the rule is tested without
     *     a server
     */
    ContentTreeSource(ItemCatalog items, ToIntFunction<String> stackSizes) {
        this.items = items;
        this.stackSizes = stackSizes;
    }

    @Override
    public String id() {
        return "content";
    }

    @Override
    public ListSourceDescription describe() {
        return new ListSourceDescription(
                new SelfDescription("Content tree",
                        "The live content as packs, folders and entries, one location at a time",
                        List.of(
                                new ParameterSchema("arg", ParameterType.STRING, false, "path",
                                        "The GUI argument that holds the location (empty is the root)", null, null),
                                new ParameterSchema("search", ParameterType.STRING, false, null,
                                        "The session variable that holds a search; while it has text, every entry "
                                                + "is listed flat", null, null)),
                        "1.0.0"),
                null,
                List.of(
                        ContextVariable.of("entry.kind", ParameterType.STRING, "pack, folder or item"),
                        ContextVariable.of("entry.name", ParameterType.STRING,
                                "The pack, the folder or the item's plain display name"),
                        ContextVariable.of("entry.label", ParameterType.STRING,
                                "The name to show: an item's display name as authored (with its formatting), "
                                        + "otherwise the plain name"),
                        ContextVariable.of("entry.path", ParameterType.STRING,
                                "The location a pack or folder opens; empty for an item"),
                        ContextVariable.of("entry.count", ParameterType.INTEGER,
                                "How many items are under a pack or folder; 1 for an item"),
                        ContextVariable.of("entry.id", ParameterType.STRING,
                                "The id of the item that is handed out; empty for a pack or folder"),
                        ContextVariable.of("entry.type", ParameterType.STRING,
                                "item, block or furniture for an item row; empty otherwise"),
                        ContextVariable.of("entry.stack", ParameterType.INTEGER,
                                "The item's full stack size; 1 for a pack or folder"),
                        ContextVariable.of("entry.detail", ParameterType.STRING,
                                "A line for the lore: the id of an item, how many items a folder holds"),
                        ContextVariable.of("entry.search", ParameterType.STRING,
                                "The lower-cased text a search matches: an item's id and plain name")),
                false);
    }

    @Override
    public Params parse(ParamReader params) {
        String arg = params.text("arg", "path");
        if (!NAME.matcher(arg).matches()) {
            throw new IllegalArgumentException("arg '" + arg + "' is not an argument name (a-z, 0-9, _)");
        }
        String search = params.optionalText("search").orElse(null);
        if (search != null && !NAME.matcher(search).matches()) {
            throw new IllegalArgumentException("search '" + search + "' is not a variable name (a-z, 0-9, _)");
        }
        return new Params(arg, search);
    }

    @Override
    public List<Map<String, Object>> entries(Params parameters, LogicContext context) {
        RuntimeContentIndex index = index();
        var out = new ArrayList<Map<String, Object>>();
        if (parameters.search() != null && hasText(context.resolve(List.of("gui", "session", parameters.search())))) {
            for (RuntimeContentIndex.Entry entry : index.entries()) {
                out.add(row(new RuntimeContentIndex.Node(RuntimeContentIndex.Kind.ENTRY, entry.name(), "", 1, entry)));
            }
            return out;
        }
        Object location = context.resolve(List.of("gui", "args", parameters.arg()));
        for (RuntimeContentIndex.Node node : index.children(location == null ? "" : String.valueOf(location))) {
            out.add(row(node));
        }
        return out;
    }

    private RuntimeContentIndex index() {
        RuntimeRegistries registries = items.current().orElse(null);
        RuntimeContentIndex view = registries == null ? null : registries.view(RuntimeContentIndex.KEY);
        return view == null ? RuntimeContentIndex.empty() : view;
    }

    private static boolean hasText(Object value) {
        return (value instanceof String || value instanceof Number) && !String.valueOf(value).isBlank();
    }

    private Map<String, Object> row(RuntimeContentIndex.Node node) {
        var row = new LinkedHashMap<String, Object>();
        switch (node.kind()) {
            case PACK, FOLDER -> {
                row.put("kind", node.kind() == RuntimeContentIndex.Kind.PACK ? "pack" : "folder");
                row.put("name", node.name());
                row.put("label", node.name());
                row.put("path", node.path());
                row.put("count", node.count());
                row.put("id", "");
                row.put("type", "");
                row.put("stack", 1);
                row.put("detail", node.count() == 1 ? "1 item" : node.count() + " items");
                row.put("search", node.name().toLowerCase(java.util.Locale.ROOT));
            }
            case ENTRY -> {
                RuntimeContentIndex.Entry entry = node.entry();
                row.put("kind", "item");
                row.put("name", entry.name());
                row.put("label", entry.label());
                row.put("path", "");
                row.put("count", 1);
                row.put("id", entry.id().toString());
                row.put("type", entry.type().name().toLowerCase(java.util.Locale.ROOT));
                row.put("stack", stackSizeOf(entry));
                row.put("detail", entry.id().toString());
                row.put("search", entry.searchKey());
            }
        }
        return row;
    }

    /** The full stack of the entry's item: its base material's, or {@value #DEFAULT_STACK} when that cannot be read. */
    private int stackSizeOf(RuntimeContentIndex.Entry entry) {
        RuntimeRegistries registries = items.current().orElse(null);
        var item = registries == null ? null : registries.item(entry.id()).orElse(null);
        if (item == null || item.material() == null) {
            return DEFAULT_STACK;
        }
        return Math.max(1, stackSizes.applyAsInt(item.material()));
    }

    /** The full stack of a base material by name; {@value #DEFAULT_STACK} when it is unknown or no server can say. */
    private static int materialStackSize(String name) {
        try {
            Material material = Material.matchMaterial(name);
            return material == null ? DEFAULT_STACK : material.getMaxStackSize();
        } catch (RuntimeException | LinkageError unreadable) {
            return DEFAULT_STACK;
        }
    }
}
