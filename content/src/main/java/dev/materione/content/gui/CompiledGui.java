package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.content.gui.logic.ClickMap;
import dev.materione.content.gui.logic.GuiRules;
import java.util.List;

/**
 * The runtime-facing model of one compiled GUI (gui.md §4, §5): what a container screen needs to open, and
 * nothing the pack needs. The background is inside {@link #title()} as a glyph, so the runtime sends the
 * title and the slot table and draws no raster itself.
 *
 * <p>Items come in two groups (logic.md §6.3). A slot that every item layer fills with a plain id and no
 * override is a static {@link Slot}, resolved once at apply time. A slot with a condition, a templated or
 * named source, an override or an animation is a {@link Placement}: the window resolves it for each viewer
 * when it renders, and the layers of that slot compete by layer order (the topmost visible one wins).
 *
 * @param id the GUI resource
 * @param rows chest rows, 1 to 6
 * @param title the container title as a MiniMessage string: the background glyph, shift
 *     tags and offset-font text runs, in draw order
 * @param accessibleTitle the GUI's plain title, for narration and title-less clients
 * @param slots the static item in each slot the GUI fills, sorted by slot
 * @param clicks which layers a click on each container slot reaches, topmost first (logic.md §6.2)
 * @param rules the GUI's authored {@code behavior:} and {@code flows:}, for the runtime to compile
 * @param playerInventory whether the viewer's own inventory is usable while the GUI is open (logic.md §6.5)
 * @param inputs the container slots that take the player's items, by slot, sorted (logic.md §25)
 * @param placements the per-viewer item layers, in layer order (logic.md §6.3)
 * @param refresh the {@code refresh:} clock in ticks, 0 when off (logic.md §6.3)
 */
public record CompiledGui(
        NamespacedId id, int rows, String title, String accessibleTitle, List<Slot> slots,
        ClickMap clicks, GuiRules rules, PlayerInventory playerInventory, List<Input> inputs,
        List<Placement> placements, int refresh) {

    /** The canvas width of every chest GUI (vanilla container width). */
    public static final int WIDTH = 176;

    public CompiledGui {
        slots = List.copyOf(slots);
        clicks = clicks == null ? ClickMap.EMPTY : clicks;
        rules = rules == null ? GuiRules.EMPTY : rules;
        playerInventory = playerInventory == null ? PlayerInventory.FREE : playerInventory;
        inputs = List.copyOf(inputs);
        placements = List.copyOf(placements);
        if (refresh < 0) {
            throw new IllegalArgumentException("refresh is 0 (off) or a whole number of ticks");
        }
    }

    /** A GUI with playerInventory and inputs, and no per-viewer items or clock. */
    public CompiledGui(NamespacedId id, int rows, String title, String accessibleTitle, List<Slot> slots,
            ClickMap clicks, GuiRules rules, PlayerInventory playerInventory, List<Input> inputs) {
        this(id, rows, title, accessibleTitle, slots, clicks, rules, playerInventory, inputs, List.of(), 0);
    }

    /** A GUI with per-viewer items or clock, and the viewer's inventory free. */
    public CompiledGui(NamespacedId id, int rows, String title, String accessibleTitle, List<Slot> slots,
            ClickMap clicks, GuiRules rules, List<Placement> placements, int refresh) {
        this(id, rows, title, accessibleTitle, slots, clicks, rules, PlayerInventory.FREE, List.of(), placements, refresh);
    }

    /** A GUI with no clickable layers and no logic: the v1 shape. */
    public CompiledGui(NamespacedId id, int rows, String title, String accessibleTitle, List<Slot> slots) {
        this(id, rows, title, accessibleTitle, slots, ClickMap.EMPTY, GuiRules.EMPTY, PlayerInventory.FREE, List.of(), List.of(), 0);
    }

    /** A GUI with clicks and rules, and no per-viewer items or clock: the M6 G1 shape. */
    public CompiledGui(NamespacedId id, int rows, String title, String accessibleTitle, List<Slot> slots,
            ClickMap clicks, GuiRules rules) {
        this(id, rows, title, accessibleTitle, slots, clicks, rules, PlayerInventory.FREE, List.of(), List.of(), 0);
    }

    /**
     * One container slot that takes the player's items.
     *
     * @param slot the chest slot, 0 at the top-left
     * @param layer the input layer that claims the slot (its topmost layer)
     * @param accept the layer's {@code accept:} as authored, or null to accept anything
     */
    public record Input(int slot, String layer, Object accept) {}

    /** The canvas height of a chest with {@code rows} rows: {@code 114 + rows·18}. */
    public static int height(int rows) {
        return 114 + rows * 18;
    }

    /**
     * One filled slot.
     *
     * @param slot the chest slot, 0 at the top-left, row by row
     * @param item the Materione item or vanilla material, resolved at apply time
     * @param amount how many, 1 to 64
     */
    public record Slot(int slot, NamespacedId item, int amount) {}

    /**
     * One item layer that is resolved per viewer when the window renders.
     *
     * @param layerId the layer's id
     * @param order the layer's index in the GUI's layer list: a higher index is a higher layer and wins its slot
     * @param slot the chest slot
     * @param visibleIf the authored {@code visible_if}, or null when the layer is always shown
     * @param item the parsed item layer: its source, overrides and animation
     */
    public record Placement(
            String layerId, int order, int slot, Object visibleIf,
            dev.materione.content.gui.ItemComponent.Layer item) {

        /**
         * Whether the placement changes while the window is open: a condition, a template, a viewer-bound
         * source or an animation. The refresh clock re-evaluates only these; the others keep the item they
         * resolved when the window was built.
         */
        public boolean dynamic() {
            return visibleIf != null || item.dynamic();
        }
    }
}
