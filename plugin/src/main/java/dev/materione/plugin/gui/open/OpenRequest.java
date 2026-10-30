package dev.materione.plugin.gui.open;

import dev.materione.api.NamespacedId;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import java.util.Objects;

/**
 * One request to open a GUI: what every way in (the command, the API, the editor preview, the
 * {@code gui.open} action and {@code gui.back}) hands the {@link GuiOpener}.
 *
 * @param viewer who opens it
 * @param gui which GUI
 * @param args the arguments as given, name to text; the gate types them
 * @param cause how the open came about; decides whether the window being replaced goes on the back stack
 * @param restore for {@link Cause#BACK}, the session to bring back (its variables and page state)
 */
public record OpenRequest(
        Viewer viewer, NamespacedId gui, Map<String, String> args, Cause cause, GuiSession.Snapshot restore) {

    /** How an open came about. */
    public enum Cause {
        /** The runtime API: {@code /materione gui open} and the editor's preview both come through it. */
        API,
        /** A flow's {@code gui.open}. */
        ACTION,
        /** A flow's {@code gui.back}: the open that pops the back stack instead of growing it. */
        BACK
    }

    public OpenRequest {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(gui, "gui");
        args = Map.copyOf(args);
        Objects.requireNonNull(cause, "cause");
    }

    /** A fresh open. */
    public static OpenRequest of(Viewer viewer, NamespacedId gui, Map<String, String> args, Cause cause) {
        return new OpenRequest(viewer, gui, args, cause, null);
    }

    /** The open that brings a remembered session back. */
    public static OpenRequest back(Viewer viewer, GuiSession.Snapshot snapshot) {
        return new OpenRequest(viewer, snapshot.gui(), snapshot.args(), Cause.BACK, snapshot);
    }
}
