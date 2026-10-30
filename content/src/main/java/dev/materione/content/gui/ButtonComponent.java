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
import java.util.List;

/**
 * {@code button}: a raised bevel drawn as a clickable-looking control (gui.md §2).
 * Every click on a GUI is cancelled; a {@code gui.click} rule naming its layer decides what the click does
 * (logic.md §6.2). Raster target.
 */
public final class ButtonComponent implements GuiComponent {

    /** The highlight and shadow thickness of a button (a panel uses 2). */
    static final int BUTTON_EDGE = 1;

    @Override
    public String name() {
        return "button";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Button",
                "A raised control face; a gui.click rule that names its layer runs when it is clicked",
                List.of(
                        new ParameterSchema("x", ParameterType.INTEGER, false, "0",
                                "Left edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "X", "placement", 10, false, "number", "", "115")
                                        .withRange(-512.0, 512.0, "px")),
                        new ParameterSchema("y", ParameterType.INTEGER, false, "0",
                                "Top edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Y", "placement", 20, false, "number", "", "70")
                                        .withRange(-512.0, 512.0, "px")),
                        new ParameterSchema("width", ParameterType.INTEGER, true, null,
                                "Width in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Width", "placement", 30, false, "number", "", "52")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("height", ParameterType.INTEGER, true, null,
                                "Height in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Height", "placement", 40, false, "number", "", "20")
                                        .withRange(0.0, 512.0, "px")),
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
                GuiBevel.read(configuration));
    }

    /** A parsed button: a raised bevel of the given size at the given position. */
    public record Layer(int x, int y, int width, int height, GuiBevel bevel) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }

        @Override
        public ClickArea clickArea(TextureSource textures) {
            return new ClickArea.Rect(x, y, width, height);
        }

        @Override
        public void paint(RasterCanvas canvas, TextureSource textures) {
            Bevels.raised(canvas, x, y, width, height, BUTTON_EDGE,
                    bevel.outline(), bevel.highlight(), bevel.shadow(), bevel.fill());
        }
    }
}
