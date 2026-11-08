package dev.materione.content.gui;

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
import java.util.List;

/**
 * {@code panel}: the raised frame behind a GUI, a large {@code button} bevel
 * (gui.md §2, §4). Raster target; the 2-pixel edge is the panel's rule.
 */
public final class PanelComponent implements GuiComponent {

    /** The highlight and shadow thickness of a panel (a button uses 1). */
    static final int PANEL_EDGE = 2;

    @Override
    public String name() {
        return "panel";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Panel",
                "A raised frame: the chest background, or any large raised area behind other layers",
                List.of(
                        new ParameterSchema("x", ParameterType.INTEGER, false, "0",
                                "Left edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "X", "placement", 10, false, "number", "", "0")
                                        .withRange(-512.0, 512.0, "px")),
                        new ParameterSchema("y", ParameterType.INTEGER, false, "0",
                                "Top edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Y", "placement", 20, false, "number", "", "0")
                                        .withRange(-512.0, 512.0, "px")),
                        new ParameterSchema("width", ParameterType.INTEGER, true, null,
                                "Width in GUI pixels (a chest is 176)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Width", "placement", 30, false, "number", "", "176")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("height", ParameterType.INTEGER, true, null,
                                "Height in GUI pixels (a three-row chest is 168)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Height", "placement", 40, false, "number", "", "168")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("corners", ParameterType.ENUM, false, "square",
                                "Outer corners: square (the 1-px cut of every earlier panel) or vanilla (the chest's own rounded corners, pixel for pixel)",
                                List.of("square", "vanilla"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Corners", "appearance", 45, false, "select", "", "square")
                                        .withOptions(List.of(
                                                ParameterSchema.EditorOption.of("vanilla", "Vanilla",
                                                        "The chest's rounded corners (new panels start here)"),
                                                ParameterSchema.EditorOption.of("square", "Square",
                                                        "The 1-px cut corner of earlier panels")))),
                        new ParameterSchema("style", ParameterType.RAW, false, null,
                                "Bevel colours: {fill, highlight, shadow, outline} as #RRGGBB or #AARRGGBB",
                                null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Bevel colours", "appearance", 50, true, "advanced", "", ""))),
                "1.0.0");
    }

    @Override
    public RenderTarget target() {
        return RenderTarget.RASTER;
    }

    @Override
    public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
        return new Layer(
                configuration.integer("x", 0, -512, 512),
                configuration.integer("y", 0, -512, 512),
                configuration.requiredInteger("width", 0, 512),
                configuration.requiredInteger("height", 0, 512),
                GuiBevel.read(configuration),
                corners(configuration));
    }

    /** An absent {@code corners} is {@code square}: every file written before #278 keeps its look. */
    static Bevels.Corners corners(GuiComponentConfiguration configuration) {
        return switch (configuration.text("corners", "square")) {
            case "square" -> Bevels.Corners.SQUARE;
            case "vanilla" -> Bevels.Corners.VANILLA;
            default -> throw new IllegalArgumentException(
                    "layer " + configuration.layerId() + ": corners must be square or vanilla");
        };
    }

    /** A parsed panel: a raised bevel of the given size at the given position, with the given corners. */
    public record Layer(int x, int y, int width, int height, GuiBevel bevel, Bevels.Corners corners)
            implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }

        @Override
        public void paint(RasterCanvas canvas, TextureSource textures) {
            Bevels.raised(canvas, x, y, width, height, PANEL_EDGE, corners,
                    bevel.outline(), bevel.highlight(), bevel.shadow(), bevel.fill());
        }
    }
}
