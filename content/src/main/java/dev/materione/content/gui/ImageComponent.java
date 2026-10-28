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
 * {@code image}: a texture from the pack placed on the canvas (gui.md §2). Its
 * natural size applies unless {@code width}/{@code height} are given. Raster target.
 */
public final class ImageComponent implements GuiComponent {

    @Override
    public String name() {
        return "image";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Image",
                "A texture from the pack, placed on the GUI background",
                List.of(
                        new ParameterSchema("image", ParameterType.REFERENCE, true, null,
                                "Texture under the pack's textures/ (extension implied)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Image", "appearance", 10, false, "texture", "texture", "gui/heart")),
                        new ParameterSchema("x", ParameterType.INTEGER, false, "0",
                                "Left edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "X", "placement", 20, false, "number", "", "0")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("y", ParameterType.INTEGER, false, "0",
                                "Top edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Y", "placement", 30, false, "number", "", "0")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("width", ParameterType.INTEGER, false, null,
                                "Drawn width in GUI pixels; absent keeps the texture's own width", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Width", "placement", 40, true, "number", "", "")
                                        .withRange(1.0, 512.0, "px")),
                        new ParameterSchema("height", ParameterType.INTEGER, false, null,
                                "Drawn height in GUI pixels; absent keeps the texture's own height", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Height", "placement", 50, true, "number", "", "")
                                        .withRange(1.0, 512.0, "px"))),
                "1.0.0");
    }

    @Override
    public RenderTarget target() {
        return RenderTarget.RASTER;
    }

    @Override
    public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
        boolean sized = configuration.parameters().containsKey("width")
                || configuration.parameters().containsKey("height");
        return new Layer(
                GuiTextures.required(configuration, "image"),
                configuration.integer("x", 0, 0, 512),
                configuration.integer("y", 0, 0, 512),
                sized ? configuration.requiredInteger("width", 1, 512) : 0,
                sized ? configuration.requiredInteger("height", 1, 512) : 0,
                sized);
    }

    /**
     * A parsed image. {@code sized} says whether the drawn size was authored; when it is
     * not, {@code width}/{@code height} are 0 and the renderer uses the texture's own size.
     */
    public record Layer(String texture, int x, int y, int width, int height, boolean sized)
            implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }
    }
}
