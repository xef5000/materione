package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.CompiledGuiLayer;

/**
 * One accepted layer of a GUI, bottom to top in authored order (gui.md §2).
 * The common fields belong to the gui type; {@code compiled} is the component's
 * immutable result for the layer's own fields.
 *
 * @param id stable id, unique inside its GUI ({@code [a-z0-9_]+})
 * @param component the registry id of the component ({@code materione:panel})
 * @param name the optional display name in the layers panel (and an item layer's item name), or null
 * @param visible hidden layers stay in the file but are not compiled
 * @param locked editor-only affordance, preserved in the file
 * @param opacity 0 to 100, applied to raster layers; 100 for every other target
 * @param compiled the component's parsed, immutable layer
 * @param visibleIf the authored {@code visible_if:} condition (logic.md §6.3), or null when the layer is
 *     always shown; kept as authored and compiled by the engine
 */
public record GuiLayer(
        String id,
        NamespacedId component,
        String name,
        boolean visible,
        boolean locked,
        int opacity,
        CompiledGuiLayer compiled,
        Object visibleIf) {

    /** A layer with no {@code visible_if}. */
    public GuiLayer(
            String id, NamespacedId component, String name, boolean visible, boolean locked, int opacity,
            CompiledGuiLayer compiled) {
        this(id, component, name, visible, locked, opacity, compiled, null);
    }
}
