package dev.materione.content.gui;

import dev.materione.api.gui.GuiComponentConfiguration;
import java.util.Map;
import java.util.Set;

/**
 * The colours of a Minecraft-style bevel (gui.md §4): {@code fill}, {@code highlight},
 * {@code shadow} and {@code outline}, as ARGB. Shared by {@code panel}, {@code button}
 * and {@code slots}, so a panel is a large button and a slot well is an inset button.
 * The defaults are the vanilla chest palette; a layer's {@code style:} overrides any of them.
 */
public record GuiBevel(int fill, int highlight, int shadow, int outline) {

    /** The vanilla container grey, white highlight, dark shadow and black outline. */
    public static final GuiBevel VANILLA = new GuiBevel(0xFFC6C6C6, 0xFFFFFFFF, 0xFF555555, 0xFF000000);

    private static final Set<String> KEYS = Set.of("fill", "highlight", "shadow", "outline");

    /**
     * Reads a layer's {@code style:} mapping. Unknown keys are rejected rather than
     * ignored, so a misspelt colour name cannot silently render the default.
     */
    public static GuiBevel read(GuiComponentConfiguration configuration) {
        Map<String, Object> style = configuration.mapping("style");
        for (String key : style.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "layer " + configuration.layerId() + ": style." + key
                                + " is not one of fill, highlight, shadow, outline");
            }
        }
        var colours = new GuiComponentConfiguration(
                configuration.guiId(), configuration.layerId(), configuration.rows(), style);
        return new GuiBevel(
                colours.color("fill", VANILLA.fill()),
                colours.color("highlight", VANILLA.highlight()),
                colours.color("shadow", VANILLA.shadow()),
                colours.color("outline", VANILLA.outline()));
    }
}
