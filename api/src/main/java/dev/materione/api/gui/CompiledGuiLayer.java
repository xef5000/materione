package dev.materione.api.gui;

/**
 * The immutable result of {@link GuiComponent#parse}: one layer, validated and
 * ready to render. Implementations hold only parsed values; they never read
 * the filesystem, the registry or the server.
 *
 * <p>Rendering is implemented by the GUI compiler (gui.md §4). Each method is
 * called only for the target it names, so a component implements exactly the
 * one that matches its {@link #target()}. The defaults throw
 * {@link UnsupportedOperationException}: an addon that declares a target must
 * implement the matching method. The compiler contains any exception a layer
 * throws and skips only that layer.
 */
public interface CompiledGuiLayer {

    /** The surface this layer draws on; equals its component's {@link GuiComponent#target()}. */
    RenderTarget target();

    /**
     * Where this layer can be clicked (logic.md §6.2). The default is {@link ClickArea#NONE}, so a
     * component that has nothing to click (a decorative panel) declares nothing, and an addon
     * component opts in by returning its rectangle or slots. The GUI compiler folds every layer's
     * area into the GUI's click map once, at compile time.
     *
     * @param textures decoded pixels of the owning pack's textures, for a layer whose size is its
     *        texture's (an image without an authored size); a missing texture means no area
     */
    default ClickArea clickArea(TextureSource textures) {
        return ClickArea.NONE;
    }

    /**
     * Paints this layer into a transparent layer canvas of canvas size. The compiler
     * composites that canvas onto the background with the layer's opacity, in authored
     * order. Called for {@link RenderTarget#RASTER} layers only. A component that cannot
     * read a texture it needs throws {@link IllegalStateException}; the layer is skipped.
     *
     * @param canvas the layer's own canvas, canvas-sized, transparent at the start
     * @param textures decoded pixels of the owning pack's textures, for layers that draw one
     */
    default void paint(RasterCanvas canvas, TextureSource textures) {
        throw new UnsupportedOperationException("this component declares no raster rendering");
    }

    /**
     * Emits this layer's title text runs. Called for {@link RenderTarget#TEXT} layers only.
     */
    default void drawText(TitleSink sink) {
        throw new UnsupportedOperationException("this component declares no title text");
    }

    /**
     * Places this layer's item in its slot. Called for {@link RenderTarget#SLOT} layers only.
     */
    default void placeSlots(SlotSink sink) {
        throw new UnsupportedOperationException("this component declares no slot placement");
    }
}
