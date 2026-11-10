package dev.materione.content.gui;

import dev.materione.api.gui.ClickArea;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.GuiContainer;
import dev.materione.api.gui.RasterCanvas;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.TextureSource;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.gui.raster.Bevels;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code slots}: the inset slot wells of the container, the player inventory, or both
 * (gui.md §2, §4). One 18 × 18 inset well per slot, at the slot's 16 × 16 item origin
 * minus one pixel; raster target.
 *
 * <p>Geometry comes from the container profile (gui.md §12.5.1): container item origins are its slots, and the
 * player inventory is its {@code inventory} origin for three rows and the hotbar 58 px below, on the 18-px pitch.
 */
public final class SlotsComponent implements GuiComponent {

    /** Which vanilla slot area the wells cover. */
    public enum Region {
        CONTAINER,
        PLAYER,
        ALL
    }

    /**
     * What an input layer asks for when the player's items sit in its slots (gui-input-durability.md §5.8, §10):
     * {@code best_effort} is today's behaviour and the default; {@code required} asks for the crash-safe path.
     *
     * <p>The runtime reads it (row P of #247): a {@code required} layer takes the escrow path (every click on its
     * slots is applied by the plugin in one step with the player's escrow account, overflow is owed and never
     * dropped), and is refused, its slots accepting nothing, where the platform cannot support it
     * ({@code MTR-0100}). It is <strong>not yet verified by the kill-boundary tests</strong> (rows B and H of #247),
     * so nothing here calls it crash-safe.
     */
    public enum Durability {
        BEST_EFFORT("best_effort"),
        REQUIRED("required");

        private final String key;

        Durability(String key) {
            this.key = key;
        }

        /** The authored spelling. */
        public String key() {
            return key;
        }

        /** The value for an authored spelling, or null when it is neither {@code required} nor {@code best_effort}. */
        public static Durability of(String key) {
            for (Durability value : values()) {
                if (value.key.equals(key)) {
                    return value;
                }
            }
            return null;
        }
    }

    /** The well's side: an 18 px inset around a 16 px item origin. */
    static final int WELL = 18;

    @Override
    public String name() {
        return "slots";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Slots",
                "Inset wells for the container slots, the player inventory, or both",
                List.of(
                        new ParameterSchema("region", ParameterType.ENUM, false, "all",
                                "Which slot area gets wells: container, player or all", List.of("container", "player", "all"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Region", "placement", 10, false, "select", "", "all")
                                        .withOptions(List.of(
                                                ParameterSchema.EditorOption.of("container", "Container",
                                                        "The container's own slots"),
                                                ParameterSchema.EditorOption.of("player", "Player inventory",
                                                        "The player's inventory and hotbar"),
                                                ParameterSchema.EditorOption.of("all", "Both",
                                                        "Container and player inventory")))),
                        new ParameterSchema("style", ParameterType.RAW, false, null,
                                "Well colours: {fill, highlight, shadow} as #RRGGBB or #AARRGGBB",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Well colours", "appearance", 20, true, "advanced", "", "")),
                        new ParameterSchema("input", ParameterType.BOOLEAN, false, "false",
                                "Input slots: the player's items may be placed in these container slots "
                                        + "(logic.md §6.5). Requires a region that includes the container",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Input slots", "behavior", 30, false, "toggle", "", "false")),
                        new ParameterSchema("accept", ParameterType.RAW, false, null,
                                "Input slots only: a condition on slot.item that an item must meet to be placed "
                                        + "(an expression or a predicate, logic.md §2.3)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Accept", "behavior", 40, true, "advanced", "", "")),
                        new ParameterSchema("durable", ParameterType.ENUM, false, "best_effort",
                                "Input slots only: how the player's items are protected while they sit in these slots. "
                                        + "required puts the items in the player's escrow account in the same step as every "
                                        + "click, never drops them in the world (what does not fit is owed, /materione claim), "
                                        + "and is refused on a server that cannot support it; best_effort is the journal of "
                                        + "earlier builds. Ignored on a layer that is not an input",
                                List.of("best_effort", "required"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Durability", "behavior", 35, false, "select", "", "best_effort")
                                        .withOptions(List.of(
                                                ParameterSchema.EditorOption.of("best_effort", "Best effort",
                                                        "Today's behaviour: items in these slots are returned on close, "
                                                                + "but a server crash can lose or duplicate them. Input slots only."),
                                                ParameterSchema.EditorOption.of("required", "Required",
                                                        "Items in these slots are tracked in the player's saved data in the "
                                                                + "same step as every click and are never dropped in the world; "
                                                                + "what does not fit is owed (/materione claim). The slots accept "
                                                                + "nothing on a server that cannot support it. Not yet verified "
                                                                + "by the crash tests."))))),
                "1.3.0");
    }

    @Override
    public RenderTarget target() {
        return RenderTarget.RASTER;
    }

    @Override
    public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
        Region region = switch (configuration.text("region", "all")) {
            case "container" -> Region.CONTAINER;
            case "player" -> Region.PLAYER;
            case "all" -> Region.ALL;
            default -> throw new IllegalArgumentException(
                    "layer " + configuration.layerId() + ": region must be container, player or all");
        };
        boolean input = configuration.bool("input", false);
        if (input && region == Region.PLAYER) {
            throw new IllegalArgumentException(
                    "layer " + configuration.layerId() + ": input slots need the container, so region must be container or all");
        }
        String durableKey = configuration.text("durable", Durability.BEST_EFFORT.key());
        Durability durable = Durability.of(durableKey);
        if (durable == null) {
            // a durability that cannot be read is never guessed: guessing best_effort would quietly weaken
            // what the author asked for, so the layer is skipped and says why
            throw new IllegalArgumentException("layer " + configuration.layerId()
                    + ": durable must be required or best_effort, not '" + durableKey + "'");
        }
        return new Layer(region, GuiBevel.read(configuration, GuiBevel.SLOT_WELL), configuration.container(),
                input, configuration.parameters().get("accept"), durable);
    }

    /**
     * The hotbar's distance below the first row of the player's inventory, in pixels: the vanilla inventory's three
     * rows, then the hotbar 58 px below the first (gui.md §12.2.3, the same for every container).
     */
    private static final int HOTBAR_GAP = 58;

    /**
     * The item origins ({@code x, y} of the 16 × 16 slot, the well is one pixel up and left) a region covers in
     * {@code container}, in container-then-player order. The container's slots are the profile's {@code slot(i)}; the
     * player's are its {@code inventory} origin and the hotbar below it (gui.md §12.5.2).
     */
    public static List<int[]> itemOrigins(Region region, GuiContainer container) {
        var origins = new ArrayList<int[]>();
        if (region == Region.CONTAINER || region == Region.ALL) {
            for (int slot = 0; slot < container.slotCount(); slot++) {
                origins.add(new int[] {container.slotX(slot), container.slotY(slot)});
            }
        }
        if (region == Region.PLAYER || region == Region.ALL) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    origins.add(new int[] {container.inventoryX() + col * GuiContainer.PITCH,
                            container.inventoryY() + row * GuiContainer.PITCH});
                }
            }
            for (int col = 0; col < 9; col++) {
                origins.add(new int[] {container.inventoryX() + col * GuiContainer.PITCH,
                        container.inventoryY() + HOTBAR_GAP});
            }
        }
        return origins;
    }

    /**
     * Parsed slot wells: which area, the inset colours, and the container the wells are drawn in (its geometry).
     *
     * @param input whether the container slots take the player's items (logic.md §25)
     * @param accept the authored {@code accept:} condition as written, or null; compiled by the runtime
     *     (it needs the flow engine), and read only when {@code input} is true
     * @param durable the authored {@code durable:} ({@link Durability#BEST_EFFORT} when absent); meaningful only
     *     when {@code input} is true; the runtime reads it (row P of #247)
     */
    public record Layer(Region region, GuiBevel bevel, GuiContainer container, boolean input, Object accept,
                        Durability durable)
            implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }

        /** The container's own slots when the wells cover them; the player inventory is not part of the GUI's map. */
        @Override
        public ClickArea clickArea(TextureSource textures) {
            if (region == Region.PLAYER) {
                return ClickArea.NONE;
            }
            var slots = new java.util.HashSet<Integer>();
            for (int slot = 0; slot < container.slotCount(); slot++) {
                slots.add(slot);
            }
            return new ClickArea.Slots(slots);
        }

        @Override
        public void paint(RasterCanvas canvas, TextureSource textures) {
            for (int[] origin : itemOrigins(region, container)) {
                Bevels.inset(canvas, origin[0] - 1, origin[1] - 1, WELL, WELL,
                        bevel.highlight(), bevel.shadow(), bevel.fill());
            }
        }
    }
}
