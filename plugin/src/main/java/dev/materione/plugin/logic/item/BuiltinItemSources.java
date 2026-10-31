package dev.materione.plugin.logic.item;

import dev.materione.api.logic.ItemSource;
import dev.materione.plugin.logic.support.ItemCatalog;
import java.util.List;

/**
 * The built-in item sources of logic.md §4.4, registered through the public {@code item-sources} registry
 * like an addon's: {@code materione:item} (the default: plain ids, vanilla materials and templates),
 * {@code materione:head} and {@code materione:player_slot}. The optional integrations add their own while
 * their plugins run.
 */
public final class BuiltinItemSources {

    private BuiltinItemSources() {}

    /** @param items the live item registries the default source resolves Materione ids against */
    public static List<ItemSource<?>> all(ItemCatalog items) {
        return List.of(new MaterioneItemSource(items), new HeadItemSource(), new PlayerSlotItemSource());
    }
}
