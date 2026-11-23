package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import java.util.Objects;

/**
 * A view's kind and definition: what {@code %materione_view_open%} and {@code %materione_view_last%} answer, written
 * {@code <kind>:<id>} ({@code gui:shop:menu}), so a placeholder consumer can tell a GUI from a dialog of the same id.
 *
 * @param kind the view kind (a content type name)
 * @param definition the view definition
 */
public record ViewRef(String kind, NamespacedId definition) {

    public ViewRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(definition, "definition");
    }

    @Override
    public String toString() {
        return kind + ":" + definition;
    }
}
