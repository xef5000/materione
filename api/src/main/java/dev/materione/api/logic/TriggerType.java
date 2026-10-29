package dev.materione.api.logic;

/**
 * One event a rule can answer, in the {@code triggers} registry (logic.md §4.3): {@code click},
 * {@code place}, later {@code gui.click}. A trigger is a <em>description</em> only. The runtime
 * that owns the event (the item listener, the block and furniture services, the GUI runtime)
 * constructs the {@link LogicContext} and asks the engine to dispatch; registering a trigger makes
 * the event known to the compiler, the context-schema check and the studio, it does not make
 * anything fire it. That is why {@link TriggerDescription#firedBy()} is part of the description.
 *
 * <p>A rule names a trigger in {@code on:}: unqualified for Materione's own ({@code on: click:right}
 * is trigger {@code click} with detail {@code right}), qualified for an addon's
 * ({@code on: example:storm}). A first segment that names a Materione trigger is never read as a
 * namespace.
 */
public interface TriggerType {

    /** The local trigger name as written after {@code on:} ({@code click}, {@code gui.click}). */
    String id();

    /** Filters, context schema, cancellability and who fires it. */
    TriggerDescription describe();
}
