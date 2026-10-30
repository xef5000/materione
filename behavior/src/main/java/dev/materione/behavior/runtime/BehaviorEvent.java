package dev.materione.behavior.runtime;

import dev.materione.api.logic.Cancellation;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.logic.MapLogicContext;

/**
 * One fired event flowing through the engine: the normalized taxonomy key,
 * its detail token (e.g. {@code left}/{@code right} for clicks), the run's
 * {@link LogicContext} (variable tree plus platform facets), and an opaque
 * platform payload (the Bukkit event) that the bridge needs back. The cancel
 * flag is engine-side — it is the {@link Cancellation#FACET} facet — so the
 * {@code cancel} action stays platform-neutral; the anchor gives deferred
 * continuations a thread locality hint.
 */
public final class BehaviorEvent implements Cancellation {

    private final String eventType;
    private final String detail;
    private final MapLogicContext context;
    private final Object payload;
    private final Object anchor;
    private Object cancelKey;
    private boolean cancelRequested;

    /**
     * @param context the run's context; the event adds the cancellation facet to it
     */
    public BehaviorEvent(
            String eventType, String detail, MapLogicContext context, Object payload, Object anchor) {
        this.eventType = eventType;
        this.detail = detail;
        this.context = context.with(Cancellation.FACET, this);
        this.payload = payload;
        this.anchor = anchor;
    }

    public String eventType() {
        return eventType;
    }

    public String detail() {
        return detail == null ? "" : detail;
    }

    /** The context actions run against. */
    public LogicContext context() {
        return context;
    }

    /** The same variable tree as the expression engine sees it (rule conditions evaluate against it). */
    public EvaluationContext evaluation() {
        return context.evaluation();
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

    /**
     * Marks the runs this event starts as cancellable by {@code key} through
     * {@code FlowInterpreter.cancel(key)}: a GUI session passes its own identity so closing the
     * window can end the runs it started (logic.md §5.2). Events without a key start runs that only
     * their player's departure ends.
     */
    public BehaviorEvent withCancelKey(Object key) {
        this.cancelKey = key;
        return this;
    }

    /** The cancellation key set by {@link #withCancelKey}, or null. */
    public Object cancelKey() {
        return cancelKey;
    }

    /** Requests cancellation of the underlying event (applied by the bridge after the chain). */
    public void requestCancel() {
        cancelRequested = true;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    @Override
    public void request() {
        requestCancel();
    }

    @Override
    public boolean requested() {
        return cancelRequested;
    }
}
