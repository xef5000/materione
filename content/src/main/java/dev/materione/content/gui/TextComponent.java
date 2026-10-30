package dev.materione.content.gui;

import dev.materione.api.gui.TextureSource;
import dev.materione.api.gui.ClickArea;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.TitleSink;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;

/**
 * {@code text}: a styled run of title text at a position (gui.md §2, §4). It is
 * title text, not baked pixels, so it stays crisp and can take placeholders later
 * (§8). Title target.
 */
public final class TextComponent implements GuiComponent {

    /** Vanilla container title colour: a dark grey that reads on the chest background. */
    static final int DEFAULT_COLOR = 0xFF404040;

    private static final int MAX_LENGTH = 128;

    @Override
    public String name() {
        return "text";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Text",
                "A line of title text, with a first-party small-caps font or the client's own font",
                List.of(
                        new ParameterSchema("text", ParameterType.STRING, true, null,
                                "The text to show (up to 128 characters)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Text", "appearance", 10, false, "text", "", "Shop")),
                        new ParameterSchema("x", ParameterType.INTEGER, false, "0",
                                "Left edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "X", "placement", 20, false, "number", "", "8")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("y", ParameterType.INTEGER, false, "0",
                                "Top edge in GUI pixels", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Y", "placement", 30, false, "number", "", "6")
                                        .withRange(0.0, 512.0, "px")),
                        new ParameterSchema("font", ParameterType.ENUM, false, "smallcaps",
                                "smallcaps (shipped with the pack) or default (the client's font)",
                                List.of("smallcaps", "default"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Font", "appearance", 40, false, "select", "", "smallcaps")
                                        .withOptions(List.of(
                                                ParameterSchema.EditorOption.of("smallcaps", "Small caps",
                                                        "First-party font, previewed pixel-exact"),
                                                ParameterSchema.EditorOption.of("default", "Default",
                                                        "The client's own font; the preview is approximate")))),
                        new ParameterSchema("color", ParameterType.STRING, false, "#404040",
                                "Text colour as #RRGGBB or #AARRGGBB", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Colour", "appearance", 50, false, "text", "", "#404040"))),
                "1.0.0");
    }

    @Override
    public RenderTarget target() {
        return RenderTarget.TEXT;
    }

    @Override
    public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
        String text = configuration.requiredText("text");
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "layer " + configuration.layerId() + ": text is longer than " + MAX_LENGTH + " characters");
        }
        TitleSink.Font font = switch (configuration.text("font", "smallcaps")) {
            case "smallcaps" -> TitleSink.Font.SMALLCAPS;
            case "default" -> TitleSink.Font.DEFAULT;
            default -> throw new IllegalArgumentException(
                    "layer " + configuration.layerId() + ": font must be smallcaps or default");
        };
        return new Layer(
                configuration.integer("x", 0, 0, 512),
                configuration.integer("y", 0, 0, 512),
                text,
                font,
                configuration.color("color", DEFAULT_COLOR));
    }

    /** A parsed text run: where it sits, what it says, how it looks. */
    public record Layer(int x, int y, String text, TitleSink.Font font, int argb) implements CompiledGuiLayer {

        @Override
        public RenderTarget target() {
            return RenderTarget.TEXT;
        }

        /** The run's ink box: its measured advance wide and one glyph cell high, from its own origin. */
        @Override
        public ClickArea clickArea(TextureSource textures) {
            boolean smallcaps = font == TitleSink.Font.SMALLCAPS;
            int width = smallcaps ? SmallcapsFont.measure(text) : VanillaAscii.measure(text);
            int height = smallcaps ? SmallcapsFont.GLYPH_ROWS : GuiFonts.VANILLA_ASCII_HEIGHT;
            return new ClickArea.Rect(x, y, width, height);
        }

        @Override
        public void drawText(TitleSink sink) {
            sink.run(x, y, text, font, argb);
        }
    }
}
