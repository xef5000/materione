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
 * {@code button}: a raised bevel drawn as a clickable-looking control (gui.md §2).
 * Its clicks are v1-cancelled; bindings arrive with the logic seam (§8). Raster target.
 */
public final class ButtonComponent implements GuiComponent {

    @Override
    public String name() {
        return "button";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Button",
                "A raised control face; in this version it looks clickable and every click is cancelled",
                List.of(
                        new ParameterSchema("x", ParameterType.INTEGER, false, "0",
                                "Left edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "X", "placement", 10, false, "number", "", "115")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("y", ParameterType.INTEGER, false, "0",
                                "Top edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Y", "placement", 20, false, "number", "", "70")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("width", ParameterType.INTEGER, true, null,
                                "Width in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Width", "placement", 30, false, "number", "", "52")
                                        .withRange(1.0, 512.0, "px")),
                        new ParameterSchema("height", ParameterType.INTEGER, true, null,
                                "Height in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Height", "placement", 40, false, "number", "", "20")
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

    /** A parsed button: a raised bevel of the given size at the given position. */
    public record Layer(int x, int y, int width, int height, GuiBevel bevel) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }
    }
}
