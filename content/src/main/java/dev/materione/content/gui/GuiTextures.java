package dev.materione.content.gui;

import dev.materione.api.gui.GuiComponentConfiguration;

/**
 * Texture references inside a GUI layer. Like every texture reference in the pack
 * they name a file under {@code textures/} without its extension (gui.md §2:
 * {@code image: gui/shop/main/art}); a trailing {@code .png} is accepted and dropped.
 */
final class GuiTextures {

    private static final String PATTERN = "[A-Za-z0-9_][A-Za-z0-9_./-]*";

    private GuiTextures() {}

    /** The normalized texture path of a required reference field. */
    static String required(GuiComponentConfiguration configuration, String key) {
        String value = configuration.requiredText(key);
        String path = value.endsWith(".png") ? value.substring(0, value.length() - ".png".length()) : value;
        if (!path.matches(PATTERN) || path.contains("..") || path.endsWith("/")) {
            throw new IllegalArgumentException(
                    "layer " + configuration.layerId() + ": " + key + " must be a texture path under textures/");
        }
        return path;
    }
}
