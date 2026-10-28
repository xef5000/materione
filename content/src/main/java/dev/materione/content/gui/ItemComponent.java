package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/**
 * {@code item}: one real item in one container slot (gui.md §2, §4). The item is a
 * reference resolved at apply time, like a recipe result: a Materione item or a
 * vanilla material. Slot target.
 */
public final class ItemComponent implements GuiComponent {

    /** The largest chest (six rows) has 54 slots, indexed 0..53. */
    static final int MAX_SLOT = 53;

    private static final int MAX_AMOUNT = 64;

    @Override
    public String name() {
        return "item";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Item",
                "One item shown in a container slot: a Materione item or a vanilla material",
                List.of(
                        new ParameterSchema("slot", ParameterType.INTEGER, true, null,
                                "Chest slot, 0 at the top-left, row by row; must lie inside the GUI's rows",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Slot", "placement", 10, false, "number", "", "13")
                                        .withRange(0.0, (double) MAX_SLOT, "")),
                        new ParameterSchema("item", ParameterType.REFERENCE, true, null,
                                "A Materione item (ns:id) or a vanilla material (minecraft:diamond_sword)",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Item", "appearance", 20, false, "item", "pack-item", "shop:gui/main/sword")),
                        new ParameterSchema("amount", ParameterType.INTEGER, false, "1",
                                "How many to show, 1 to 64", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Amount", "appearance", 30, false, "number", "", "1")
                                        .withRange(1.0, (double) MAX_AMOUNT, ""))),
                "1.0.0");
    }

    @Override
    public RenderTarget target() {
        return RenderTarget.SLOT;
    }

    @Override
    public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
        int slot = configuration.requiredInteger("slot", 0, MAX_SLOT);
        if (slot >= configuration.rows() * 9) {
            throw new IllegalArgumentException(
                    "layer " + configuration.layerId() + ": slot " + slot + " is outside the GUI's "
                            + configuration.rows() + " row(s)");
        }
        String item = configuration.requiredText("item");
        NamespacedId id = item.contains(":")
                ? NamespacedId.parse(item)
                : NamespacedId.of("materione", item);
        return new Layer(slot, id, configuration.integer("amount", 1, 1, MAX_AMOUNT));
    }

    /** A parsed slot item: the slot, the item reference and how many. */
    public record Layer(int slot, NamespacedId item, int amount) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.SLOT;
        }
    }
}
