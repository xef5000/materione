package dev.materione.plugin.gui.browse;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeBlock;
import dev.materione.core.runtime.RuntimeBlocks;
import dev.materione.core.runtime.RuntimeFurniture;
import dev.materione.core.runtime.RuntimeFurnitures;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The browser's pure model (M6): the rows built from one registry snapshot, the categories derived from them, and the
 * search. No Bukkit types, so every rule here is unit-tested without a server.
 *
 * <p>Categories come from the registries, not from a second list. An item's category is its {@code category:} metadata
 * when authored, otherwise its pack (the namespace, which the pipeline sets from the pack id). A block or a furniture
 * is filed with its placement item, so it sits in the same group as the thing a player takes to place it; one with no
 * placement item falls back to its own pack. Synthetic placement items are never rows of their own (the block or
 * furniture row hands them out).
 */
public final class BrowseCatalog {

    /**
     * A category as the browser's first screen shows it.
     *
     * @param key the grouping key (lower-case)
     * @param label the label the group is shown under: the spelling of its first row in sort order (rows sort by name
     *     within a group), so a group never shows two spellings
     * @param count how many rows it holds
     * @param icon the item whose stack represents the group, or null when no row hands one out
     */
    public record Category(String key, String label, int count, NamespacedId icon) {}

    private BrowseCatalog() {}

    /**
     * Every browsable row of one registry snapshot, sorted by category, then name (case-insensitive), then id.
     */
    public static List<BrowseEntry> build(RuntimeRegistries registries) {
        List<BrowseEntry> rows = new ArrayList<>();
        for (RuntimeItem item : registries.items()) {
            if (!item.synthetic()) {
                rows.add(new BrowseEntry(BrowseEntry.Kind.ITEM, item.id(), plainName(item.displayName(), item.id()),
                        categoryOf(item.category(), item.id().namespace()), item.id()));
            }
        }
        RuntimeBlocks blocks = registries.view(RuntimeBlocks.KEY);
        if (blocks != null) {
            for (RuntimeBlock block : blocks.all()) {
                // a block has no display name of its own: its row is named after its id
                rows.add(placed(BrowseEntry.Kind.BLOCK, block.id(), humanize(block.id().value()),
                        blocks.itemFor(block.id()), registries));
            }
        }
        RuntimeFurnitures furnitures = registries.view(RuntimeFurnitures.KEY);
        if (furnitures != null) {
            for (RuntimeFurniture furniture : furnitures.all()) {
                rows.add(placed(BrowseEntry.Kind.FURNITURE, furniture.id(),
                        plainName(furniture.displayName(), furniture.id()),
                        furnitures.itemFor(furniture.id()), registries));
            }
        }
        Comparator<BrowseEntry> order = Comparator.comparing((BrowseEntry entry) -> entry.categoryKey())
                .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.id().toString());
        rows.sort(order);
        return List.copyOf(rows);
    }

    /** The categories of a row list, in key order; each carries its count and the first row's item as its icon. */
    public static List<Category> categories(List<BrowseEntry> rows) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, NamespacedId> icons = new LinkedHashMap<>();
        for (BrowseEntry row : rows) {
            String key = row.categoryKey();
            labels.putIfAbsent(key, row.category().strip());
            counts.merge(key, 1, Integer::sum);
            if (row.item() != null) {
                icons.putIfAbsent(key, row.item());
            }
        }
        List<Category> out = new ArrayList<>();
        counts.keySet().stream().sorted().forEach(key ->
                out.add(new Category(key, labels.get(key), counts.get(key), icons.get(key))));
        return List.copyOf(out);
    }

    /** The rows of one category key, in the list's order. */
    public static List<BrowseEntry> inCategory(List<BrowseEntry> rows, String key) {
        return rows.stream().filter(row -> row.categoryKey().equals(key)).toList();
    }

    /**
     * The rows a search matches. The query is split on whitespace and every word must appear in the row's id or name
     * (case-insensitive), so {@code ruby sword} finds {@code shop:ruby_sword} and not {@code shop:ruby_dagger}. A blank
     * query matches every row.
     */
    public static List<BrowseEntry> search(List<BrowseEntry> rows, String query) {
        List<String> words = words(query);
        if (words.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> {
                    String key = row.searchKey();
                    return words.stream().allMatch(key::contains);
                })
                .toList();
    }

    /** The search words of a query: lower-cased, split on whitespace, blanks dropped. */
    public static List<String> words(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(word -> !word.isEmpty())
                .toList();
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

    /** The group a row is filed under: the authored category when present, otherwise the pack. */
    static String categoryOf(String authored, String pack) {
        return authored == null || authored.isBlank() ? pack : authored.strip();
    }

    /**
     * A block or a furniture row: named as given, filed under its placement item's category (its own pack when it has
     * no placement item), and handing out that placement item.
     */
    private static BrowseEntry placed(
            BrowseEntry.Kind kind, NamespacedId id, String name,
            Optional<NamespacedId> placement, RuntimeRegistries registries) {
        RuntimeItem item = placement.flatMap(registries::item).orElse(null);
        String category = item != null ? categoryOf(item.category(), id.namespace()) : id.namespace();
        return new BrowseEntry(kind, id, name, category, placement.orElse(null));
    }

    /** {@code amethyst_lamp} to {@code Amethyst Lamp}. */
    static String humanize(String value) {
        var out = new StringBuilder();
        for (String word : value.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
