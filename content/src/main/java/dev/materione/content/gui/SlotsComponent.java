package dev.materione.content.gui;

import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/**
 * {@code slots}: the inset slot wells of the container, the player inventory,
 * or both (gui.md §2, §4). An inset bevel per 18×18 well; raster target.
 */
public final class SlotsComponent implements GuiComponent {

    /** Which vanilla slot area the wells cover. */
    public enum Region {
        CONTAINER,
        PLAYER,
        ALL
    }

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
                                        "Well colours", "appearance", 20, true, "advanced", "", ""))),
                "1.0.0");
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
        return new Layer(region, GuiBevel.read(configuration));
    }

    /** Parsed slot wells: which area, and the inset colours. */
    public record Layer(Region region, GuiBevel bevel) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }
    }
}
