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
 * {@code paint}: a pixel layer backed by one PNG the layer owns (gui.md §2, §11.2). The studio
 * trims the PNG to its painted pixels and records where it sits with {@code x}/{@code y} and its
 * size with {@code width}/{@code height}. A layer without {@code x}/{@code y} sits at the origin, as
 * the canvas-sized layers written before #277 do. A layer with a zero {@code width} or
 * {@code height} has no pixels yet: it draws nothing and needs no texture. Raster target.
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
                "A pixel layer painted in the studio, stored as one PNG trimmed to its pixels",
                List.of(
                        new ParameterSchema("image", ParameterType.REFERENCE, true, null,
                                "PNG under the pack's textures/ (the studio writes it)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Painting", "appearance", 10, false, "texture", "texture", "gui/shop/main/art")),
                        position("x", "X", "Left edge of the painted pixels in GUI pixels", 20),
                        position("y", "Y", "Top edge of the painted pixels in GUI pixels", 30),
                        size("width", "Width", "Width of the painted pixels; 0 when nothing is painted", 40),
                        size("height", "Height", "Height of the painted pixels; 0 when nothing is painted", 50)),
                "1.0.0");
    }

    private static ParameterSchema position(String key, String label, String description, int order) {
        return new ParameterSchema(key, ParameterType.INTEGER, false, "0", description, null, null)
                .withEditor(new ParameterSchema.EditorMetadata(label, "placement", order, false, "number", "", "0")
                        .withRange(-1024.0, 1024.0, "px"));
    }

    private static ParameterSchema size(String key, String label, String description, int order) {
        return new ParameterSchema(key, ParameterType.INTEGER, false, null, description, null, null)
                .withEditor(new ParameterSchema.EditorMetadata(label, "placement", order, true, "number", "", "")
                        .withRange(0.0, 1024.0, "px"));
    }

    @Override
    public RenderTarget target() {
        return RenderTarget.RASTER;
    }

    @Override
    public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
        int x = configuration.integer("x", 0, -1024, 1024);
        int y = configuration.integer("y", 0, -1024, 1024);
        boolean empty = configuration.integer("width", 1, 0, 1024) == 0
                || configuration.integer("height", 1, 0, 1024) == 0;
        return new Layer(empty ? null : GuiTextures.required(configuration, "image"), x, y);
    }

    /**
     * A parsed paint layer: the texture it owns ({@code null} when nothing is painted) and where
     * its top-left sits. Its bitmap is copied 1:1 (never scaled); what falls past the art cap is
     * clipped by the canvas.
     */
    public record Layer(String texture, int x, int y) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }

        @Override
        public void paint(RasterCanvas canvas, TextureSource textures) {
            if (texture == null) {
                return;
            }
            TextureSource.Pixels pixels = textures.texture(texture).orElseThrow(
                    () -> new IllegalStateException("texture " + texture + " is missing or unreadable"));
            Blit.draw(canvas, x, y, pixels.width(), pixels.height(), pixels);
        }
    }
}
