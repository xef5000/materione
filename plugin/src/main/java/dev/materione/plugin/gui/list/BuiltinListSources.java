package dev.materione.plugin.gui.list;

import dev.materione.api.logic.ListSource;
import dev.materione.plugin.logic.support.ItemCatalog;
import java.util.List;

/**
 * The built-in list sources (logic.md §4.5, §6.7): online players, a pack's items, the content tree, a declared
 * variable's pieces and static entries. They register through the public API under {@code materione:}, exactly as an addon's would.
 */
public final class BuiltinListSources {

    private BuiltinListSources() {}

    /** The built-ins, in the order the editor lists them.
     *
     * @param items the live item registries {@code pack_items} reads
     */
    public static List<ListSource<?>> all(ItemCatalog items) {
        return List.of(new OnlinePlayersSource(), new PackItemsSource(items), new ContentTreeSource(items),
                new VariableListSource(), new StaticListSource());
    }
}
