package dev.materione.api.logic;

import dev.materione.api.registry.SelfDescription;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The self-description of a {@link Recipe} (logic.md §4.6, §30): what it is called, what it does, and the typed
 * parameters the editor's form is drawn from. Parameter names are unique.
 *
 * @param name the name the editor shows ({@code Pay to receive})
 * @param description one paragraph the editor shows as help text
 * @param params the parameters, in the order the form shows them
 */
public record RecipeDescription(String name, String description, List<RecipeParam> params) {

    public RecipeDescription {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a recipe needs a name");
        }
        description = description == null ? "" : description;
        params = params == null ? List.of() : List.copyOf(params);
        Set<String> seen = new HashSet<>();
        for (RecipeParam param : params) {
            if (!seen.add(param.name())) {
                throw new IllegalArgumentException("parameter '" + param.name() + "' is declared twice");
            }
        }
    }

    /** The parameter called {@code name}, or null when the recipe does not declare one. */
    public RecipeParam param(String name) {
        for (RecipeParam param : params) {
            if (param.name().equals(name)) {
                return param;
            }
        }
        return null;
    }

    /** The registry's view of this description: the same name, text and parameters, in the shape every entry carries. */
    public SelfDescription self() {
        return new SelfDescription(name, description,
                params.stream().map(RecipeParam::schema).toList(), "2.0.0", null);
    }
}
