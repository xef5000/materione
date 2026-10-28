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
import dev.materione.content.gui.raster.Blit;
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

    /**
     * A parsed paint layer: the texture it owns. Its bitmap is copied 1:1 with its top-left
     * at the canvas origin (never scaled); a larger bitmap is clipped, a smaller one leaves the
     * rest transparent.
     */
    public record Layer(String texture) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }

        @Override
        public void paint(RasterCanvas canvas, TextureSource textures) {
            TextureSource.Pixels pixels = textures.texture(texture).orElseThrow(
                    () -> new IllegalStateException("texture " + texture + " is missing or unreadable"));
            Blit.draw(canvas, 0, 0, pixels.width(), pixels.height(), pixels);
        }
    }
}
