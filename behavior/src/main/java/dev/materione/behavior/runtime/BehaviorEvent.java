package dev.materione.behavior.runtime;

import dev.materione.behavior.expr.EvaluationContext;

/**
 * One fired event flowing through the engine: the normalized taxonomy key,
 * its detail token (e.g. {@code left}/{@code right} for clicks), the
 * expression-visible variable tree, and an opaque platform payload (the
 * Bukkit event) that executors may need. The cancel flag is engine-side so
 * {@code cancel} actions stay platform-neutral; the anchor gives deferred
 * continuations a thread locality hint.
 */
public final class BehaviorEvent {

    private final String eventType;
    private final String detail;
    private final EvaluationContext context;
    private final Object payload;
    private final Object anchor;
    private boolean cancelRequested;

    public BehaviorEvent(
            String eventType, String detail, EvaluationContext context, Object payload, Object anchor) {
        this.eventType = eventType;
        this.detail = detail;
        this.context = context;
        this.payload = payload;
        this.anchor = anchor;
    }

    public String eventType() {
        return eventType;
    }

    public String detail() {
        return detail == null ? "" : detail;
    }

    public EvaluationContext context() {
        return context;
    }

    /** The opaque platform attachment (the originating Bukkit event, if any). */
    @SuppressWarnings("unchecked")
    public <T> T payload() {
        return (T) payload;
    }

    /** Thread-locality hint for deferred work (typically the player), or null. */
    public Object anchor() {
        return anchor;
    }

    /** Requests cancellation of the underlying event (applied by the bridge after the chain). */
    public void requestCancel() {
        cancelRequested = true;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }
}
