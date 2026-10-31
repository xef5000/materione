package dev.materione.plugin.logic.item;

import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.ItemSource;
import dev.materione.api.logic.ItemSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.MaterioneStacks;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.support.ItemRef;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * {@code materione:item}: the default item source (logic.md §4.4). A plain string in an {@code item:} is its
 * payload, so {@code shop:gui/sword} and {@code minecraft:diamond} resolve here, and so does a template that
 * renders one of them ({@code ${entry.item}}). The resolution is the one {@code give_item} uses, so a bare
 * name means the same thing in a GUI and in a rule.
 */
public final class MaterioneItemSource implements ItemSource<TemplateText> {

    private final ItemCatalog items;

    /** @param items the live item registries, read at each resolve so a reload is seen by the next render */
    public MaterioneItemSource(ItemCatalog items) {
        this.items = items;
    }

    @Override
    public String id() {
        return "item";
    }

    @Override
    public ItemSourceDescription describe() {
        return ItemSourceDescription.of(new SelfDescription(
                "Item",
                "A Materione item or a vanilla material, by id, or a template that renders one",
                List.of(new ParameterSchema("item", ParameterType.STRING, true, null,
                        "ns:id, minecraft:material, or a template such as ${entry.item}", null, null)),
                "1.0.0"),
                "item");
    }

    @Override
    public TemplateText parse(ParamReader params) {
        return params.template("item");
    }

    @Override
    public ItemResolution resolve(TemplateText template, LogicContext context) {
        String text = template.render(context).trim();
        RuntimeRegistries registries = items.current().orElse(null);
        ItemRef ref;
        try {
            ref = ItemRef.resolve(text, registries);
        } catch (IllegalArgumentException unresolved) {
            return ItemResolution.unresolved(unresolved.getMessage());
        }
        return switch (ref) {
            case ItemRef.Vanilla vanilla -> {
                Material material = Material.matchMaterial(vanilla.material());
                yield material == null || !material.isItem()
                        ? ItemResolution.unresolved("'minecraft:" + vanilla.material().toLowerCase(java.util.Locale.ROOT)
                                + "' is not a vanilla item")
                        : ItemResolution.ok(new ItemStack(material));
            }
            case ItemRef.Materione materione -> registries.item(materione.id())
                    .map(live -> ItemResolution.ok(MaterioneStacks.build(MaterioneStacks.specOf(live))))
                    .orElseGet(() -> ItemResolution.unresolved("the item '" + text + "' is not live"));
        };
    }
}
