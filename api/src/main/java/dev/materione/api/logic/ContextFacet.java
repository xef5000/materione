package dev.materione.api.logic;

import dev.materione.api.NamespacedId;
import java.util.Objects;

/**
 * The typed key of one thing a {@link LogicContext} can hold: the player, the open GUI, the list
 * entry under the cursor. A facet is how an action asks the world for something without the engine
 * knowing what it is, so the engine stays Bukkit-free and a test can hand an action a fake.
 *
 * <p>Whoever owns the type declares the facet: the core ones live in {@link Facets}; a runtime that
 * owns its own types (the GUI runtime and its session) declares its own beside them, and an addon
 * declares facets under its own namespace. Facets are compared by {@link #id()} alone, so a facet
 * declared once and shared as a constant is the norm.
 *
 * @param id the facet's identity, namespaced so two addons never collide
 * @param type the type of the value the facet holds
 * @param <T> the value type
 */
public record ContextFacet<T>(NamespacedId id, Class<T> type) {

    public ContextFacet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }

    /** Declares a facet. */
    public static <T> ContextFacet<T> of(NamespacedId id, Class<T> type) {
        return new ContextFacet<>(id, type);
    }

    /** Declares a facet from {@code namespace:name}. */
    public static <T> ContextFacet<T> of(String id, Class<T> type) {
        return new ContextFacet<>(NamespacedId.parse(id), type);
    }

    /** Checked cast of a value stored under this facet. */
    public T cast(Object value) {
        return type.cast(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContextFacet<?> facet && id.equals(facet.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "facet " + id;
    }
}
