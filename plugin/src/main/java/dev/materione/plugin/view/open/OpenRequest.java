package dev.materione.plugin.view.open;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.view.ViewerRecord;
import java.util.Map;
import java.util.Objects;

/**
 * One request to open a view: what every way in (the command, the API, the editor preview, the {@code view.open}
 * action and {@code view.back}) hands the kind's opener.
 *
 * @param viewer who opens it
 * @param kind the view kind to open ({@code gui}): the content type of {@code view}
 * @param view which definition
 * @param args the arguments as given, name to text; the gate types them
 * @param cause how the open came about; decides whether the window being replaced goes on the view history
 * @param restore for {@link Cause#BACK}, the record to bring back (its session values and kind state)
 */
public record OpenRequest(
        Viewer viewer, String kind, NamespacedId view, Map<String, String> args, Cause cause,
        ViewerRecord.Snapshot restore, StackRef stack) {

    /** The kind the commands and the runtime API open: they are the GUI's ways in. */
    public static final String GUI = "gui";

    /** How an open came about. */
    public enum Cause {
        /** The runtime API: {@code /materione gui open} and the editor's preview both come through it. */
        API,
        /** A flow's {@code view.open}. */
        ACTION,
        /** A flow's {@code view.back}: the open that pops the view history instead of growing it. */
        BACK
    }

    public OpenRequest {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(view, "view");
        args = Map.copyOf(args);
        Objects.requireNonNull(cause, "cause");
    }

    /** A fresh open of a GUI (the command and the runtime API). */
    public static OpenRequest of(Viewer viewer, NamespacedId gui, Map<String, String> args, Cause cause) {
        return of(viewer, GUI, gui, args, cause, null);
    }

    /** A fresh GUI open bound to the opening run's stack, when there is one. */
    public static OpenRequest of(
            Viewer viewer, NamespacedId gui, Map<String, String> args, Cause cause, StackRef stack) {
        return new OpenRequest(viewer, GUI, gui, args, cause, null, stack);
    }

    /** A fresh open of a view of any kind. */
    public static OpenRequest of(
            Viewer viewer, String kind, NamespacedId view, Map<String, String> args, Cause cause) {
        return of(viewer, kind, view, args, cause, null);
    }

    /** A fresh open of any kind bound to the opening run's stack. */
    public static OpenRequest of(
            Viewer viewer, String kind, NamespacedId view, Map<String, String> args, Cause cause, StackRef stack) {
        return new OpenRequest(viewer, kind, view, args, cause, null, stack);
    }

    /** The open that brings a remembered record back, of whatever kind it was. */
    public static OpenRequest back(Viewer viewer, ViewerRecord.Snapshot snapshot) {
        return new OpenRequest(viewer, snapshot.kind(), snapshot.definition(), snapshot.args(), Cause.BACK, snapshot,
                snapshot.stack());
    }
}
