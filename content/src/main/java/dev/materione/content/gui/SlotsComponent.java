package dev.materione.content.gui;

import dev.materione.api.gui.ClickArea;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
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
 * <p>Chest geometry (the shared-vector README): container item origins are
 * {@code x = 8 + col·18}, {@code y = 18 + row·18}; the player inventory is
 * {@code y = rows·18 + 31 + row·18} for its three rows and the hotbar
 * {@code y = rows·18 + 89}, both with {@code x = 8 + col·18}.
 */
public final class SlotsComponent implements GuiComponent {

    /** Which vanilla slot area the wells cover. */
    public enum Region {
        CONTAINER,
        PLAYER,
        ALL
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
                                                        "The chest's own slots"),
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
                                        "Accept", "behavior", 40, true, "advanced", "", ""))),
                "1.1.0");
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
        return new Layer(region, GuiBevel.read(configuration, GuiBevel.SLOT_WELL), configuration.rows(),
                input, configuration.parameters().get("accept"));
    }

    /**
     * The item origins ({@code x, y} of the 16 × 16 slot, the well is one pixel up and left)
     * a region covers in a chest of {@code rows} rows, in container-then-player order.
     */
    public static List<int[]> itemOrigins(Region region, int rows) {
        var origins = new ArrayList<int[]>();
        if (region == Region.CONTAINER || region == Region.ALL) {
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < 9; col++) {
                    origins.add(new int[] {8 + col * 18, 18 + row * 18});
                }
            }
        }
        if (region == Region.PLAYER || region == Region.ALL) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    origins.add(new int[] {8 + col * 18, rows * 18 + 31 + row * 18});
                }
            }
            for (int col = 0; col < 9; col++) {
                origins.add(new int[] {8 + col * 18, rows * 18 + 89});
            }
        }
        return origins;
    }

    /**
     * Parsed slot wells: which area, the inset colours, and the chest's row count for geometry.
     *
     * @param input whether the container slots take the player's items (logic.md §25)
     * @param accept the authored {@code accept:} condition as written, or null; compiled by the runtime
     *     (it needs the flow engine), and read only when {@code input} is true
     */
    public record Layer(Region region, GuiBevel bevel, int rows, boolean input, Object accept)
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
            for (int slot = 0; slot < rows * 9; slot++) {
                slots.add(slot);
            }
            return new ClickArea.Slots(slots);
        }

        @Override
        public void paint(RasterCanvas canvas, TextureSource textures) {
            for (int[] origin : itemOrigins(region, rows)) {
                Bevels.inset(canvas, origin[0] - 1, origin[1] - 1, WELL, WELL,
                        bevel.highlight(), bevel.shadow(), bevel.fill());
            }
        }
    }
}
