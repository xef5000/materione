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
 * {@code panel}: the raised frame behind a GUI, a large {@code button} bevel
 * (gui.md §2, §4). Raster target; the 2-pixel edge is the renderer's rule (#184).
 */
public final class PanelComponent implements GuiComponent {

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
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("y", ParameterType.INTEGER, false, "0",
                                "Top edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Y", "placement", 20, false, "number", "", "0")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("width", ParameterType.INTEGER, true, null,
                                "Width in GUI pixels (a chest is 176)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Width", "placement", 30, false, "number", "", "176")
                                        .withRange(1.0, 512.0, "px")),
                        new ParameterSchema("height", ParameterType.INTEGER, true, null,
                                "Height in GUI pixels (a three-row chest is 168)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Height", "placement", 40, false, "number", "", "168")
                                        .withRange(1.0, 512.0, "px")),
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
                configuration.integer("x", 0, 0, 512),
                configuration.integer("y", 0, 0, 512),
                configuration.requiredInteger("width", 1, 512),
                configuration.requiredInteger("height", 1, 512),
                GuiBevel.read(configuration));
    }

    /** A parsed panel: a raised bevel of the given size at the given position. */
    public record Layer(int x, int y, int width, int height, GuiBevel bevel) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }
    }
}
