package dev.materione.content.gui;

import dev.materione.api.gui.GuiComponentConfiguration;
import java.util.Map;
import java.util.Set;

/**
 * The colours of a Minecraft-style bevel (gui.md §4): {@code fill}, {@code highlight},
 * {@code shadow} and {@code outline}, as ARGB. Shared by {@code panel}, {@code button} and
 * {@code slots}, so a panel is a large button and a slot well is an inset button. Each
 * component has its own defaults (the shared-vector README's table); a layer's
 * {@code style:} overrides any of them.
 */
public record GuiBevel(int fill, int highlight, int shadow, int outline) {

    /** The vanilla container grey, white highlight, dark shadow and black outline (panel, button). */
    public static final GuiBevel VANILLA = new GuiBevel(0xFFC6C6C6, 0xFFFFFFFF, 0xFF555555, 0xFF000000);

    /** The vanilla slot well: a darker grey interior, white highlight, dark shadow. The outline is unused. */
    public static final GuiBevel SLOT_WELL = new GuiBevel(0xFF8B8B8B, 0xFFFFFFFF, 0xFF373737, 0xFF000000);

    private static final Set<String> KEYS = Set.of("fill", "highlight", "shadow", "outline");

    /**
     * Reads a layer's {@code style:} mapping with the panel and button defaults. Unknown keys
     * are rejected rather than ignored, so a misspelt colour name cannot silently render the default.
     */
    public static GuiBevel read(GuiComponentConfiguration configuration) {
        return read(configuration, VANILLA);
    }

    /** Reads a layer's {@code style:} mapping, falling back to {@code defaults} for absent keys. */
    public static GuiBevel read(GuiComponentConfiguration configuration, GuiBevel defaults) {
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
                colours.color("fill", defaults.fill()),
                colours.color("highlight", defaults.highlight()),
                colours.color("shadow", defaults.shadow()),
                colours.color("outline", defaults.outline()));
    }
}
