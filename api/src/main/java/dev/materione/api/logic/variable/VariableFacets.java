package dev.materione.api.logic.variable;

import dev.materione.api.logic.ContextFacet;

/** The facet a variable-aware run reads its GUI session through (logic.md §3.1, §16 for #214). */
public final class VariableFacets {

    /** The open GUI session's variables; absent for a run that is not in a GUI. */
    public static final ContextFacet<SessionVariables> SESSION =
            ContextFacet.of("materione:session_variables", SessionVariables.class);

    private VariableFacets() {}
}
