package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.ClickArea;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.SlotSink;
import dev.materione.api.gui.TextureSource;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.gui.item.GuiTemplates;
import dev.materione.content.gui.item.ItemFrames;
import dev.materione.content.gui.item.ItemSpec;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code item}: one item in one container slot (gui.md §2, §4, logic.md §6.3). The item is an
 * {@link ItemSpec item source}: a plain id (static, resolved at apply time), a template or a named
 * source such as {@code head:} (resolved per viewer at render time). The overrides {@code name},
 * {@code lore} with {@code lore_mode}, {@code amount} and {@code glint} are templates, and {@code frames}
 * animates the item. A layer with any of those is dynamic (logic.md §6.3): the compiler records it and the
 * refresh clock re-reads it. Slot target.
 */
public final class ItemComponent implements GuiComponent {

    /** The largest chest (six rows) has 54 slots, indexed 0..53. */
    static final int MAX_SLOT = 53;

    /** The largest stack a chest slot shows. */
    public static final int MAX_AMOUNT = 64;

    private static final Set<String> LORE_MODES = Set.of("replace", "append", "prepend");

    /** How a layer's lore combines with the item's own lore (logic.md §6.3). */
    public enum LoreMode {
        /** The layer's lines replace the item's own lore. */
        REPLACE,
        /** The layer's lines follow the item's own lore. */
        APPEND,
        /** The layer's lines come before the item's own lore. */
        PREPEND;

        static LoreMode parse(String text) {
            return valueOf(text.toUpperCase(Locale.ROOT));
        }
    }

    @Override
    public String name() {
        return "item";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Item",
                "One item shown in a container slot: a Materione item, a vanilla material, a named source "
                        + "such as a player head, or a template; with templated overrides and animation",
                List.of(
                        new ParameterSchema("slot", ParameterType.INTEGER, true, null,
                                "Chest slot, 0 at the top-left, row by row; must lie inside the GUI's rows",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Slot", "placement", 10, false, "number", "", "13")
                                        .withRange(0.0, (double) MAX_SLOT, "")),
                        new ParameterSchema("item", ParameterType.REFERENCE, false, null,
                                "The item: a Materione item (ns:id), a vanilla material (minecraft:diamond_sword), "
                                        + "a template (${entry.item}), or one source such as "
                                        + "{ head: { player: \"${player.name}\" } } or { player_slot: main_hand }. "
                                        + "Exactly one of item or frames",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Item", "appearance", 20, false, "item", "pack-item", "shop:gui/main/sword")),
                        new ParameterSchema("frames", ParameterType.RAW, false, null,
                                "An animation in place of item: { every: <ticks>, items: [sources...] }; the "
                                        + "layer shows items in turn", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Frames", "appearance", 21, true, "text", "", "")),
                        new ParameterSchema("amount", ParameterType.STRING, false, "1",
                                "How many to show: a whole number from 1 to 64, or a template", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Amount", "appearance", 30, false, "text", "", "1")),
                        new ParameterSchema("name", ParameterType.STRING, false, null,
                                "The item's display name (MiniMessage, with templates). On an item layer this is "
                                        + "also the layers-panel label", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Item name", "appearance", 40, true, "text", "", "")),
                        new ParameterSchema("lore", ParameterType.LIST, false, null,
                                "Lore lines (MiniMessage, with templates); combined with the item's own lore by "
                                        + "lore_mode", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Lore", "appearance", 50, true, "structured-list", "", "")),
                        new ParameterSchema("lore_mode", ParameterType.ENUM, false, "replace",
                                "How lore combines with the item's own lore: replace, append or prepend",
                                List.of("replace", "append", "prepend"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Lore mode", "appearance", 60, true, "select", "", "replace")),
                        new ParameterSchema("glint", ParameterType.STRING, false, null,
                                "Enchantment glint: true, false, or a template that renders true or false", null,
                                null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Glint", "appearance", 70, true, "text", "", ""))),
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
            throw fail(configuration, "slot " + slot + " is outside the GUI's " + configuration.rows() + " row(s)");
        }
        Map<String, Object> raw = configuration.parameters();
        boolean hasItem = raw.containsKey("item");
        boolean hasFrames = raw.containsKey("frames");
        if (hasItem == hasFrames) {
            throw fail(configuration, "give exactly one of item (a source) or frames (an animation)");
        }
        ItemSpec item = hasItem ? itemOf(configuration, raw.get("item")) : null;
        ItemFrames frames = hasFrames ? framesOf(configuration, raw.get("frames")) : null;
        String amount = amountOf(configuration, raw.get("amount"));
        String name = nameOf(configuration);
        List<String> lore = loreOf(configuration, raw.get("lore"));
        LoreMode loreMode = loreModeOf(configuration, raw.get("lore_mode"), raw.containsKey("lore"));
        String glint = glintOf(configuration, raw.get("glint"));
        return new Layer(slot, item, frames, amount, name, lore, loreMode, glint);
    }

    private static ItemSpec itemOf(GuiComponentConfiguration configuration, Object raw) {
        try {
            return ItemSpec.parse(raw);
        } catch (IllegalArgumentException rejected) {
            throw fail(configuration, rejected.getMessage());
        }
    }

    private static ItemFrames framesOf(GuiComponentConfiguration configuration, Object raw) {
        if (!(raw instanceof Map<?, ?> mapping)) {
            throw fail(configuration, "frames is a mapping: { every: <ticks>, items: [ ... ] }");
        }
        try {
            return ItemFrames.parse(mapping);
        } catch (IllegalArgumentException rejected) {
            throw fail(configuration, rejected.getMessage());
        }
    }

    /** The amount as authored: a whole number from 1 to 64 (kept as text), or a template. */
    private static String amountOf(GuiComponentConfiguration configuration, Object raw) {
        if (raw == null) {
            return "1";
        }
        if (raw instanceof Number number) {
            return String.valueOf(wholeIn(configuration, number, "amount"));
        }
        if (raw instanceof String text && !text.isBlank()) {
            String trimmed = text.trim();
            if (GuiTemplates.isTemplated(trimmed)) {
                return trimmed;
            }
            try {
                int value = Integer.parseInt(trimmed);
                if (value >= 1 && value <= MAX_AMOUNT) {
                    return Integer.toString(value);
                }
            } catch (NumberFormatException notANumber) {
                // reported below
            }
        }
        throw fail(configuration, "amount must be a whole number from 1 to " + MAX_AMOUNT + ", or a template");
    }

    private static int wholeIn(GuiComponentConfiguration configuration, Number number, String key) {
        double decimal = number.doubleValue();
        if (Double.isFinite(decimal) && decimal == Math.rint(decimal)
                && decimal >= 1 && decimal <= MAX_AMOUNT) {
            return (int) decimal;
        }
        throw fail(configuration, key + " must be a whole number from 1 to " + MAX_AMOUNT + ", or a template");
    }

    private static String nameOf(GuiComponentConfiguration configuration) {
        try {
            return configuration.text("name", null);
        } catch (IllegalArgumentException blank) {
            throw fail(configuration, "name must be non-blank text");
        }
    }

    private static List<String> loreOf(GuiComponentConfiguration configuration, Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof String text) {
            return List.of(text);
        }
        if (raw instanceof List<?> lines) {
            var out = new java.util.ArrayList<String>(lines.size());
            for (Object line : lines) {
                if (!(line instanceof String text)) {
                    throw fail(configuration, "lore is text or a list of text lines");
                }
                out.add(text);
            }
            return List.copyOf(out);
        }
        throw fail(configuration, "lore is text or a list of text lines");
    }

    private static LoreMode loreModeOf(GuiComponentConfiguration configuration, Object raw, boolean hasLore) {
        if (raw == null) {
            return LoreMode.REPLACE;
        }
        if (!hasLore) {
            throw fail(configuration, "lore_mode needs lore");
        }
        if (raw instanceof String text && LORE_MODES.contains(text.trim().toLowerCase(Locale.ROOT))) {
            return LoreMode.parse(text.trim());
        }
        throw fail(configuration, "lore_mode is replace, append or prepend");
    }

    /** The glint as authored: {@code true} or {@code false} (kept as text), or a template; null when absent. */
    private static String glintOf(GuiComponentConfiguration configuration, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean flag) {
            return flag.toString();
        }
        if (raw instanceof String text && !text.isBlank()) {
            String trimmed = text.trim();
            if (GuiTemplates.isTemplated(trimmed)) {
                return trimmed;
            }
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
        }
        throw fail(configuration, "glint is true, false, or a template");
    }

    private static IllegalArgumentException fail(GuiComponentConfiguration configuration, String message) {
        return new IllegalArgumentException("layer " + configuration.layerId() + ": " + message);
    }

    /**
     * One parsed item layer.
     *
     * @param slot the chest slot
     * @param item the item source, or null for an animation
     * @param frames the animation, or null for a single item
     * @param amount how many, as text: a whole number from 1 to 64, or a template
     * @param name the display name template, or null
     * @param lore the lore lines (templates allowed), empty when none
     * @param loreMode how the lore combines with the item's own lore
     * @param glint {@code "true"} or {@code "false"}, a template, or null for the item's own glint
     */
    public record Layer(
            int slot, ItemSpec item, ItemFrames frames, String amount, String name, List<String> lore,
            LoreMode loreMode, String glint) implements CompiledGuiLayer {

        public Layer {
            lore = List.copyOf(lore);
            loreMode = loreMode == null ? LoreMode.REPLACE : loreMode;
        }

        @Override
        public RenderTarget target() {
            return RenderTarget.SLOT;
        }

        @Override
        public ClickArea clickArea(TextureSource textures) {
            return new ClickArea.Slots(Set.of(slot));
        }

        /**
         * The plain id this layer always shows, or empty when the layer is anything else. A plain id with no
         * overrides and no animation is static: the GUI compiler places it in the slot table, and it is
         * resolved at apply time.
         */
        public java.util.Optional<NamespacedId> staticItem() {
            if (item instanceof ItemSpec.Plain plain && frames == null && !overridden()) {
                return java.util.Optional.of(plain.id());
            }
            return java.util.Optional.empty();
        }

        /** True when the layer sets any override, so it cannot be a plain slot entry. */
        public boolean overridden() {
            return name != null || !lore.isEmpty() || !"1".equals(amount) || glint != null;
        }

        /**
         * Whether the layer depends on the viewer, the clock or a template (logic.md §6.3): a templated
         * source, a viewer-bound source, an animation, or a templated override.
         */
        public boolean dynamic() {
            return frames != null
                    || (item != null && item.dynamic())
                    || GuiTemplates.isTemplated(name)
                    || lore.stream().anyMatch(GuiTemplates::isTemplated)
                    || GuiTemplates.isTemplated(amount)
                    || GuiTemplates.isTemplated(glint);
        }

        @Override
        public void placeSlots(SlotSink sink) {
            staticItem().ifPresent(id -> sink.place(slot, id, Integer.parseInt(amount)));
        }
    }
}
