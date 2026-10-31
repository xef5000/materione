package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.session.GuiSession;

/**
 * Whether a layer is showing for a session right now (logic.md §6.3): the router skips a layer that is not,
 * and takes the next one under it. The answer is the viewer's current window, so a click is routed to what
 * the viewer sees: {@link #RENDERED} reads the last render of the window, which records the layers its
 * {@code visible_if} hid.
 */
@FunctionalInterface
public interface LayerVisibility {

    /** Every layer showing: for a host with no conditional layers (tests). */
    LayerVisibility ALL = (session, layerId) -> true;

    /** A layer is showing unless the window's last render hid it with its {@code visible_if}. */
    LayerVisibility RENDERED = (session, layerId) -> !session.rendered().hidden().contains(layerId);

    boolean visible(GuiSession session, String layerId);
}
