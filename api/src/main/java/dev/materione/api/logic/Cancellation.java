package dev.materione.api.logic;

/**
 * The handle an action uses to cancel the event that fired the rule (the {@code cancel} action,
 * and later {@code stop} with {@code cancel: true}). It is a facet ({@link #FACET}) rather than a
 * method on the context so that a host provides it only when it can honour it.
 *
 * <p>Cancellation is a request: the bridge that owns the underlying event applies it after the
 * chain finishes (a cancelled Bukkit event must be cancelled on the thread that is handling it).
 * Hosts provide the handle for every run; requesting it on a trigger that is not
 * {@linkplain TriggerDescription#cancellable() cancellable} is accepted and has no effect, which is
 * how the M2 engine always behaved.
 */
public interface Cancellation {

    /**
     * The facet that carries the handle. The same constant is exposed as
     * {@link Facets#CANCELLATION}; the engine reads it from here because {@link Facets} also holds
     * Bukkit-typed facets, and the Bukkit-free engine must never load that class.
     */
    ContextFacet<Cancellation> FACET = ContextFacet.of("materione:cancellation", Cancellation.class);

    /** Asks for the underlying event to be cancelled once the chain completes. Idempotent. */
    void request();

    /** True once {@link #request} has been called. */
    boolean requested();
}
