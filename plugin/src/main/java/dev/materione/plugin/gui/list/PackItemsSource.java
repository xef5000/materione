package dev.materione.plugin.gui.list;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.ListSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.plugin.logic.support.ItemCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code pack_items}: every live item of one pack, as {@code item} (its id), in the registry's order. Shorthand:
 * {@code pack_items: shop} lists the items of the {@code shop} pack. The read walks the whole item registry, so the
 * source is {@code cached}: a window reads it when it opens and on {@code gui.refresh}, not on every tick.
 */
public final class PackItemsSource implements ListSource<PackItemsSource.Params> {

    /** The parsed parameters: the pack whose items are listed. */
    public record Params(String pack) {}

    private final ItemCatalog items;

    /** @param items the live item registries the entries are read from */
    public PackItemsSource(ItemCatalog items) {
        this.items = items;
    }

    @Override
    public String id() {
        return "pack_items";
    }

    @Override
    public ListSourceDescription describe() {
        return new ListSourceDescription(
                new SelfDescription("Pack items", "Every live item of one pack, by its id",
                        List.of(new ParameterSchema("pack", ParameterType.STRING, true, null,
                                "The pack's namespace, such as shop", null, null)),
                        "1.0.0"),
                "pack",
                List.of(ContextVariable.of("entry.item", ParameterType.STRING, "The item's id, pack:name")),
                true);
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(params.text("pack"));
    }

    @Override
    public List<Map<String, Object>> entries(Params parameters, LogicContext context) {
        var entries = new ArrayList<Map<String, Object>>();
        items.current().ifPresent(registries -> {
            for (RuntimeItem item : registries.items()) {
                if (item.id().namespace().equals(parameters.pack())) {
                    entries.add(Map.of("item", item.id().toString()));
                }
            }
        });
        return entries;
    }
}
