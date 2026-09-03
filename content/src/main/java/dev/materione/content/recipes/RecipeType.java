package dev.materione.content.recipes;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.content.ContentType;
import dev.materione.core.content.PackEmitter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The recipe content type (M1 scope: shaped + shapeless + furnace — the
 * rest wait for M7, roadmap M1). Recipes contribute no pack output; their
 * apply happens through the platform's recipe API with a post-swap recipe
 * book resync (plugin.md §4.5). An unresolvable ingredient quarantines
 * the recipe only, never its neighbors (runtime-systems §9).
 */
public final class RecipeType implements ContentType {

    /** One parsed recipe as the runtime serves it. */
    public record Recipe(
            NamespacedId id,
            String kind,                       // shaped | shapeless | furnace
            NamespacedId result,
            List<String> shape,                // shaped only
            Map<Character, NamespacedId> shapedIngredients,
            List<NamespacedId> ingredients,    // shapeless + furnace input
            float experience,
            int cookingTime) {}

    @Override
    public String name() {
        return "recipe";
    }

    @Override
    public String directory() {
        return "recipes";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Recipe",
                "A crafting/cooking recipe: shaped, shapeless or furnace (M1); "
                        + "ingredients and result reference items through the graph",
                List.of(
                        new ParameterSchema("type", ParameterType.ENUM, true, "shaped",
                                "How the recipe is crafted. shaped: the layout matters. "
                                        + "shapeless: any arrangement. furnace: smelting.",
                                List.of("shaped", "shapeless", "furnace"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Recipe kind", "identity", 0, false, "select", "",
                                        "shaped")),
                        new ParameterSchema("result", ParameterType.REFERENCE, true, null,
                                "Resulting item id", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Result", "identity", 10, false, "reference", "item",
                                        "myserver:ruby_sword")),
                        new ParameterSchema("shape", ParameterType.LIST, false, null,
                                "Shaped rows, one string per row (shaped only)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Grid", "crafting", 10, false, "recipe-grid", "", "")),
                        new ParameterSchema("ingredients", ParameterType.RAW, false, null,
                                "Char→item map (shaped) or item list (shapeless)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Ingredients", "crafting", 20, false, "recipe-grid", "",
                                        "")),
                        new ParameterSchema("input", ParameterType.REFERENCE, false, null,
                                "Furnace input item id", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Smelts", "crafting", 30, false, "reference", "item",
                                        "myserver:ruby_ore")),
                        new ParameterSchema("experience", ParameterType.DECIMAL, false, "0",
                                "Furnace experience", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Experience", "crafting", 40, false, "number", "", "0.7")),
                        new ParameterSchema("cookingtime", ParameterType.INTEGER, false, "200",
                                "Furnace cooking time in ticks", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Cooking time (ticks)", "crafting", 50, false, "number",
                                        "", "200"))),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        Object kindValue = definition.get("type");
        if (!(kindValue instanceof String kind)) {
            return Optional.empty();
        }
        NamespacedId result = reference(id.namespace(), definition.get("result"));
        if (result == null) {
            return Optional.empty();
        }
        var shapedIngredients = new LinkedHashMap<Character, NamespacedId>();
        var ingredients = new ArrayList<NamespacedId>();
        List<String> shape = stringList(definition.get("shape"));
        switch (kind) {
            case "shaped" -> {
                if (shape.isEmpty() || !(definition.get("ingredients") instanceof Map<?, ?> map)) {
                    return Optional.empty();
                }
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || key.length() != 1) {
                        continue;
                    }
                    NamespacedId ingredient = reference(id.namespace(), entry.getValue());
                    if (ingredient == null) {
                        return Optional.empty();
                    }
                    shapedIngredients.put(key.charAt(0), ingredient);
                }
            }
            case "shapeless" -> {
                if (!(definition.get("ingredients") instanceof List<?> list)) {
                    return Optional.empty();
                }
                for (Object entry : list) {
                    NamespacedId ingredient = reference(id.namespace(), entry);
                    if (ingredient == null) {
                        return Optional.empty();
                    }
                    ingredients.add(ingredient);
                }
            }
            case "furnace" -> {
                NamespacedId input = reference(id.namespace(), definition.get("input"));
                if (input == null) {
                    return Optional.empty();
                }
                ingredients.add(input);
            }
            default -> {
                return Optional.empty();
            }
        }
        float experience = decimal(definition.get("experience"), 0f);
        int cookingTime = (int) decimal(definition.get("cookingtime"), 200);
        return Optional.of(new Recipe(
                id, kind, result, shape, shapedIngredients, ingredients, experience, cookingTime));
    }

    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter,
            dev.materione.core.content.Allocations allocations) {
        // recipes contribute no pack output; apply goes through the
        // platform recipe API (RuntimeRecipe → RecipeApplier)
    }

    static NamespacedId reference(String defaultNamespace, Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.contains(":")
                ? NamespacedId.parse(text)
                : NamespacedId.of(defaultNamespace, text);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (Object entry : list) {
            if (entry instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    private static float decimal(Object value, float fallback) {
        return value instanceof Number n ? n.floatValue() : fallback;
    }
}
