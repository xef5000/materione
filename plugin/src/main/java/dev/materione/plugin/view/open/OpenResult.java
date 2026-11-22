package dev.materione.plugin.view.open;

import dev.materione.plugin.view.ViewerRecord;

/**
 * What a kind's opener did. {@link Outcome#OPENED} means the record exists and the window is opening (it is up at
 * once, or on the viewer's next tick when they were mid-click). A known admission failure leaves the viewer's windows
 * and records as they were; an unexpected host failure can occur after the prior record has been retired and its
 * window is then closed.
 *
 * @param record the new viewer record, for {@link Outcome#OPENED}
 * @param reason why not, for every other outcome
 */
public record OpenResult(Outcome outcome, ViewerRecord record, String reason) {

    /** The shape of the answer. */
    public enum Outcome {
        OPENED,
        /** The view is unknown, or its last compile quarantined it. */
        NOT_LIVE,
        /** The open gate refused (permission, requirement). */
        DENIED,
        /** An argument does not read as its declared type; the reason is the usage line (logic.md §6.1). */
        USAGE,
        /** An opening rule (the kind's cancellable opening trigger) cancelled the open. */
        CANCELLED,
        /** The server could not open the window. */
        FAILED
    }

    public static OpenResult opened(ViewerRecord record) {
        return new OpenResult(Outcome.OPENED, record, "");
    }

    public static OpenResult refused(Outcome outcome, String reason) {
        return new OpenResult(outcome, null, reason);
    }

    public boolean ok() {
        return outcome == Outcome.OPENED;
    }
}
