package dev.materione.api.gui;

/**
 * The immutable result of {@link GuiComponent#parse}: one layer, validated and
 * ready to render. Implementations hold only parsed values; they never read
 * the filesystem, the registry or the server.
 *
 * <p>Rendering is declared here and implemented by the GUI compiler (gui.md
 * §4, issue #184). Each method is called only for the target it names, so a
 * component implements exactly the one that matches its {@link #target()}.
 * Until #184 lands, the defaults throw {@link UnsupportedOperationException};
 * nothing calls them yet.
 */
public interface CompiledGuiLayer {

    /** The surface this layer draws on; equals its component's {@link GuiComponent#target()}. */
    RenderTarget target();

    /**
     * Paints this layer into the background canvas. Called for {@link RenderTarget#RASTER}
     * layers only, in authored layer order. TODO(#184): wired by the GUI compiler's flatten step.
     */
    default void paint(RasterCanvas canvas) {
        throw new UnsupportedOperationException("GUI raster rendering is GUI 2/5 (#184)");
    }

    /**
     * Emits this layer's title text runs. Called for {@link RenderTarget#TEXT} layers only.
     * TODO(#184): wired by the GUI compiler's title assembly step.
     */
    default void drawText(TitleSink sink) {
        throw new UnsupportedOperationException("GUI title text is GUI 2/5 (#184)");
    }

    /**
     * Places this layer's item in its slot. Called for {@link RenderTarget#SLOT} layers only.
     * TODO(#184): wired by the GUI compiler's slot table step.
     */
    default void placeSlots(SlotSink sink) {
        throw new UnsupportedOperationException("GUI slot placement is GUI 2/5 (#184)");
    }
}
