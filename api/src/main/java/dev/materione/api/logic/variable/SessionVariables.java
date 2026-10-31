package dev.materione.api.logic.variable;

import java.util.Optional;

/**
 * The {@code session} variables of one open GUI (logic.md §3.1): they survive a refresh and
 * {@code gui.back}, and end with the window. The GUI runtime implements this over its
 * session and puts it into a run's context under {@link VariableFacets#SESSION}; a run with no open
 * session has no such facet, so {@code vars.session.*} reads empty and writes fail.
 */
public interface SessionVariables {

    /** The value stored under {@code name}, or empty when none. */
    Optional<Object> get(String name);

    /** Stores {@code value} under {@code name}; a null value removes it. */
    void put(String name, Object value);
}
