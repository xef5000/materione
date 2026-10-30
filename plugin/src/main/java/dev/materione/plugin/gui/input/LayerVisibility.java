package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.session.GuiSession;

/**
 * Whether a layer is showing for a session right now (logic.md §6.3): the router skips a layer that
 * is not, and takes the next one under it. Static layers are always showing; the dynamic-slots work
 * answers for {@code visible_if}. The router depends on this seam and nothing else for visibility, so
 * that work does not touch it.
 */
@FunctionalInterface
public interface LayerVisibility {

    /** Every layer showing: the answer until a layer can be conditional. */
    LayerVisibility ALL = (session, layerId) -> true;

    boolean visible(GuiSession session, String layerId);
}
