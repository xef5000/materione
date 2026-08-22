package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Map;

/**
 * One recipe as the runtime serves it — the version-agnostic projection
 * the platform's recipe applier consumes (translation to Bukkit recipes
 * happens in {@code platform/}, the only version-aware module).
 */
public record RuntimeRecipe(
        NamespacedId id,
        String kind,
        NamespacedId result,
        List<String> shape,
        Map<Character, NamespacedId> shapedIngredients,
        List<NamespacedId> ingredients,
        float experience,
        int cookingTime) {

    public RuntimeRecipe {
        shape = List.copyOf(shape);
        shapedIngredients = Map.copyOf(shapedIngredients);
        ingredients = List.copyOf(ingredients);
    }
}
