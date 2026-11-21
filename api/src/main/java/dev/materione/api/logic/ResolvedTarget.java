package dev.materione.api.logic;

/**
 * One subject a {@link TargetSpec} resolved to (logic.md §39): the thing a targeted action acts on, held for
 * exactly that step. The engine hands it to the action as the {@link #FACET} of the step's context and routes the
 * action's own-region work to {@link #anchor()}'s thread; an action reads it through {@link Subjects}, never the
 * facet directly.
 *
 * <p>The interface is Bukkit-free so the engine can carry it; the platform's implementation holds the entity.
 */
public interface ResolvedTarget {

    /**
     * The facet that carries the target for the duration of one action. The engine reads it from here because
     * {@link Facets} names Bukkit types, which the Bukkit-free engine must never load.
     */
    ContextFacet<ResolvedTarget> FACET = ContextFacet.of("materione:target", ResolvedTarget.class);

    /** The thread-locality hint the run scheduler routes by (the entity); never null. */
    Object anchor();

    /** What kind of subject this is. */
    TargetKind kind();

    /** A short human phrase for the trace and for failure reasons ({@code player Bob}, {@code zombie 1a2b3c4d}). */
    String describe();
}
