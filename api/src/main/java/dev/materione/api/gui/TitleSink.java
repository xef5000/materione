package dev.materione.api.gui;

/**
 * The title sink a {@link RenderTarget#TEXT} layer emits into (gui.md §4):
 * positioned, styled text runs. The compiler turns each run into a shift tag
 * and an offset font, so a component only names where text goes and how it
 * looks. Coordinates are integer GUI pixels; colours are ARGB.
 */
public interface TitleSink {

    /** The title fonts a text run may name (gui.md §4, "Fonts for text layers"). */
    enum Font {
        /** First-party bitmap font shipped in the pack; previewed pixel-exact. */
        SMALLCAPS,

        /** The client's own font, referenced from its vanilla bitmap textures. */
        DEFAULT
    }

    /** One text run whose top-left sits at {@code (x, y)} in GUI pixels. */
    void run(int x, int y, String text, Font font, int argb);
}
