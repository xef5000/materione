package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The declared persistent variables of one applied shadow (logic.md §3.2): every {@code variables}
 * definition the live packs compiled. It swaps with the other registries in one step, so a reload
 * that removes a declaration takes effect for the next read, and a stored value of a removed
 * declaration is simply no longer reachable (the store keeps it; nothing is deleted).
 */
public final class RuntimeVariables {

    public static final Class<RuntimeVariables> KEY = RuntimeVariables.class;

    private static final RuntimeVariables EMPTY = new RuntimeVariables(Map.of());

    private final Map<NamespacedId, VariableDeclaration> declarations;

    public RuntimeVariables(Map<NamespacedId, VariableDeclaration> declarations) {
        // insertion order is the declaration order of the packs, kept for listings
        this.declarations = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(declarations));
    }

    /** The view of a shadow with no declarations (boot state, or no {@code variable} content). */
    public static RuntimeVariables empty() {
        return EMPTY;
    }

    /** The declaration of {@code id}, or empty when no live pack declares it. */
    public Optional<VariableDeclaration> find(NamespacedId id) {
        return Optional.ofNullable(declarations.get(id));
    }

    /** Every declaration, in pack order. */
    public Collection<VariableDeclaration> all() {
        return declarations.values();
    }

    public int size() {
        return declarations.size();
    }
}
