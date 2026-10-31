package dev.materione.plugin.gui.open;

import dev.materione.plugin.gui.session.GuiSession;

/**
 * What {@link GuiOpener#open} did. {@link Outcome#OPENED} means the session exists and the window is
 * opening (it is up at once, or on the viewer's next tick when they were mid-click); every other
 * outcome left the viewer's windows and sessions as they were.
 *
 * @param session the new session, for {@link Outcome#OPENED}
 * @param reason why not, for every other outcome
 */
public record OpenResult(Outcome outcome, GuiSession session, String reason) {

    /** The shape of the answer. */
    public enum Outcome {
        OPENED,
        /** The GUI is unknown, or its last compile quarantined it. */
        NOT_LIVE,
        /** The open gate refused (permission, requirement). */
        DENIED,
        /** An argument does not read as its declared type; the reason is the usage line (logic.md §6.1). */
        USAGE,
        /** A {@code gui.opening} rule cancelled the open. */
        CANCELLED,
        /** The server could not open the window. */
        FAILED
    }

    public static OpenResult opened(GuiSession session) {
        return new OpenResult(Outcome.OPENED, session, "");
    }

    public static OpenResult refused(Outcome outcome, String reason) {
        return new OpenResult(outcome, null, reason);
    }

    public boolean ok() {
        return outcome == Outcome.OPENED;
    }
}
