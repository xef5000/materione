package dev.materione.api.gui;

/**
 * The physical surface a GUI component's output lands on (gui.md §4). Every
 * component targets exactly one; the compiler orders layers within a target
 * and fixes the order across targets.
 */
public enum RenderTarget {
    /** Pixels baked into the background glyph that covers the vanilla screen texture. */
    RASTER,

    /** Styled title text, positioned with shift tags and per-offset fonts. */
    TEXT,

    /** A real item in one container slot. */
    SLOT
}
