package dev.materione.plugin.view;

import java.util.Map;

/**
 * The kind-specific part of a {@link ViewerRecord} (views.md §3.2, §3.3): the viewer-local state of one kind
 * ({@code GuiViewerState}; later a dialog's token and a HUD's last-sent strings). It is render state and navigation
 * only: anything another viewer or a rule outside the viewer's own runs could need is state in the variable store,
 * never here (views.md §3.3).
 *
 * <p>A payload is confined to the viewer's owner thread (views.md §5.6); the record it belongs to tells it which
 * record that is, and a payload reports each mutation through {@link ViewerRecord#mutating(String)}, which is how a
 * mutation off the owner thread is caught.
 */
public interface ViewerPayload {

    /** Called once, when the payload is bound to its record. */
    default void boundTo(ViewerRecord record) {}

    /**
     * The part of this payload's state that {@code view.back} brings back with the binding and the session values
     * (a list's page, say). Immutable; empty for a kind with nothing to keep.
     */
    default Map<String, Object> snapshot() {
        return Map.of();
    }

    /** Puts a {@link #snapshot()} into this (fresh) payload. */
    default void restore(Map<String, Object> state) {}
}
