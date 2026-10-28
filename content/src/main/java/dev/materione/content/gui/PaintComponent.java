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
 * {@code paint}: a pixel layer backed by one canvas-sized PNG the layer owns
 * (gui.md §2). It has no position: the image is the whole canvas. Raster target.
 */
public final class PaintComponent implements GuiComponent {

    @Override
    public String name() {
        return "paint";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Paint",
                "A pixel layer painted in the studio, stored as one canvas-sized PNG",
                List.of(
                        new ParameterSchema("image", ParameterType.REFERENCE, true, null,
                                "Canvas-sized PNG under the pack's textures/ (the studio writes it)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Painting", "appearance", 10, false, "texture", "texture", "gui/shop/main/art"))),
                "1.0.0");
    }

    @Override
    public RenderTarget target() {
        return RenderTarget.RASTER;
    }

    @Override
    public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
        return new Layer(GuiTextures.required(configuration, "image"));
    }

    /** A parsed paint layer: the texture it owns. */
    public record Layer(String texture) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }
    }
}
