package dev.materione.api.logic;

/**
 * Thrown by {@link LogicContext#require} when the context has no value for a facet. A type that
 * can run without the facet should use {@link LogicContext#facet} and return
 * {@link ActionOutcome#failed} (or {@code false}) instead; an escaped exception is still
 * contained by the engine and counted as a failure.
 */
public final class MissingFacetException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient ContextFacet<?> facet;

    public MissingFacetException(ContextFacet<?> facet) {
        super("this context has no " + facet.id() + " (" + facet.type().getSimpleName() + ")");
        this.facet = facet;
    }

    /** The facet that was asked for. */
    public ContextFacet<?> facet() {
        return facet;
    }
}
