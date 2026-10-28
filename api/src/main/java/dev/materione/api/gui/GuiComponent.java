package dev.materione.api.gui;

import dev.materione.api.registry.SelfDescription;

/**
 * One layer kind in the {@code gui-components} registry (gui.md §3–§4) — the
 * public addon seam for GUI layers. Built-ins (panel, slots, image, paint,
 * button, text, item) register through the same registry path addons use.
 *
 * <p>A component is a factory, not a layer: {@link #parse} compiles one
 * layer's own fields into an immutable {@link CompiledGuiLayer}, which renders
 * only through the narrow sinks ({@link RasterCanvas}, {@link TitleSink},
 * {@link SlotSink}). Parsing is pure and never touches the server; the GUI
 * type owns ordering, flattening, glyph allocation and font emission.
 *
 * <p>The studio's inspector is derived from {@link #describe()}: an addon
 * component gets a schema-driven inspector without any editor change.
 */
public interface GuiComponent {

    /** The component name as a layer writes it in {@code type:} ({@code panel}). */
    String name();

    /** Editor metadata and parameter schema for this component's own fields. */
    SelfDescription describe();

    /** The one surface this component draws on. Fixed per component. */
    RenderTarget target();

    /**
     * Compiles one layer's component fields. Reject bad content with
     * {@link IllegalArgumentException}; the pipeline diagnoses and skips that
     * layer and keeps the rest of the GUI.
     */
    CompiledGuiLayer parse(GuiComponentConfiguration configuration);
}
