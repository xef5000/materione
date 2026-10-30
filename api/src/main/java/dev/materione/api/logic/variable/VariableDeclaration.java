package dev.materione.api.logic.variable;

import dev.materione.api.NamespacedId;
import java.util.Objects;

/**
 * One declared persistent variable, as the {@code variable} content type defines it (logic.md §3.2).
 *
 * <p>The id is pack-namespaced ({@code shop:coins}); the declaration's pack owns it. The default is
 * already coerced to the type, so a read of a never-written variable needs no further checks.
 *
 * @param id the pack-namespaced key
 * @param scope where the value is stored: {@link VariableScope#PLAYER} or {@link VariableScope#SERVER}
 * @param type the value's type
 * @param defaultValue the value a read returns while nothing is stored (never null)
 * @param description the author's note, shown by the studio and {@code /materione var list}
 */
public record VariableDeclaration(
        NamespacedId id, VariableScope scope, VariableType type, Object defaultValue, String description) {

    public VariableDeclaration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(type, "type");
        if (defaultValue == null) {
            defaultValue = type.defaultValue();
        }
        defaultValue = type.coerce(defaultValue).orElseThrow(() -> new IllegalArgumentException(
                id + ": default does not fit type " + type.id()));
        description = description == null ? "" : description;
    }

    /** The pack that declares the variable (the namespace of {@link #id()}). */
    public String pack() {
        return id.namespace();
    }
}
