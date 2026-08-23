package dev.materione.core.pipeline.validate;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.graph.ContentEdgeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Level-3 semantic validation: reference resolution with edit-distance
 * "did you mean" suggestions (plugin architecture §4.3). A reference is a
 * string field the content type declares as a link ({@code template:},
 * {@code model:}, {@code texture:}); unresolvable references quarantine the
 * referencing resource only — never its file, never its pack.
 */
public final class ReferenceValidator implements ValidateStage.Validator {

    /** Fields treated as references at the structural floor (slice 1). */
    public static final List<String> REFERENCE_FIELDS = List.of("template", "model", "texture");

    @Override
    public List<Diagnostic> validate(ContentResource resource, ValidationEnvironment environment) {
        var problems = new ArrayList<Diagnostic>();
        Object mapping = resource.document().data().get(resource.id().value());
        if (!(mapping instanceof java.util.Map<?, ?> definition)) {
            problems.add(new Diagnostic(
                    DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                    "resource key '" + resource.id().value() + "' is not a mapping",
                    resource.id(), null,
                    "wrap the definition: '" + resource.id().value() + ":' followed by indented fields"));
            return problems;
        }
        if ("recipe".equals(resource.contentType())) {
            return validateRecipe(resource, definition, environment, problems);
        }
        for (String field : REFERENCE_FIELDS) {
            Object value = definition.get(field);
            if (!(value instanceof String target) || target.isBlank()) {
                continue;
            }
            NamespacedId targetId = resolve(resource.id().namespace(), target);
            if (!exists(environment, targetId)) {
                problems.add(new Diagnostic(
                        DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                        "unresolved " + field + " '" + target + "'",
                        resource.id(), null,
                        "did you mean '" + closest(environment, target, field) + "'?"));
            }
        }
        if ("block".equals(resource.contentType())) {
            validateDrops(resource, definition, environment, problems);
        }
        return problems;
    }

    /**
     * Block drops: every entry of {@code drops:} must resolve to an item
     * resource or a vanilla {@code minecraft:} id — a bad entry quarantines
     * the block only.
     */
    private static void validateDrops(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(definition.get("drops") instanceof List<?> drops)) {
            return;
        }
        for (Object entry : drops) {
            if (!(entry instanceof String target) || target.isBlank()) {
                continue;
            }
            NamespacedId targetId = resolve(resource.id().namespace(), target);
            if (!isItem(environment, targetId)) {
                problems.add(new Diagnostic(
                        DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                        "block " + resource.id() + ": drop '" + target + "' is not an item",
                        resource.id(), null,
                        "drops must reference materione items or vanilla minecraft: ids"));
            }
        }
    }

    /**
     * Recipes: result + every ingredient must resolve to an item (or a
     * vanilla {@code minecraft:} id). One bad reference quarantines the
     * recipe only — never its neighbors (runtime-systems §9).
     */
    private static List<Diagnostic> validateRecipe(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        checkRecipeReference(resource, "result", definition.get("result"), environment, problems);
        if (definition.get("input") != null) {
            checkRecipeReference(resource, "input", definition.get("input"), environment, problems);
        }
        if (definition.get("ingredients") instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                checkRecipeReference(resource, "ingredient", entry.getValue(), environment, problems);
            }
        } else if (definition.get("ingredients") instanceof List<?> list) {
            for (Object entry : list) {
                checkRecipeReference(resource, "ingredient", entry, environment, problems);
            }
        }
        return problems;
    }

    private static void checkRecipeReference(
            ContentResource resource,
            String field,
            Object value,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(value instanceof String target) || target.isBlank()) {
            return;
        }
        NamespacedId targetId = resolve(resource.id().namespace(), target);
        if (!exists(environment, targetId) && !isItem(environment, targetId)) {
            problems.add(new Diagnostic(
                    DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                    "recipe " + resource.id() + ": unresolved " + field + " '" + target + "'",
                    resource.id(), null,
                    "recipe references must be materione items or vanilla minecraft: ids"));
        }
    }

    /** True when {@code id} names an item resource (recipe endpoints). */
    private static boolean isItem(ValidationEnvironment environment, NamespacedId id) {
        if (id.isReservedNamespace()) {
            return true; // vanilla item id — legality checked at apply by the platform
        }
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(id.namespace())) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if ("item".equals(resource.contentType()) && resource.id().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static NamespacedId resolve(String defaultNamespace, String raw) {
        return raw.contains(":")
                ? NamespacedId.parse(raw)
                : NamespacedId.of(defaultNamespace, raw);
    }

public static ContentEdgeType edgeTypeFor(String field) {
        return switch (field) {
            case "template" -> ContentEdgeType.TEMPLATE;
            case "model" -> ContentEdgeType.ITEM_MODEL;
            case "texture" -> ContentEdgeType.TEXTURE;
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static boolean exists(ValidationEnvironment environment, NamespacedId id) {
        if (id.isReservedNamespace()) {
            return true; // minecraft: references are vanilla by definition
        }
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(id.namespace())) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if (resource.id().equals(id)) {
                    return true;
                }
            }
        }
        return environment.knownAssets().contains(id);
    }

    private static String closest(ValidationEnvironment environment, String target, String field) {
        String best = target;
        int bestDistance = Integer.MAX_VALUE;
        for (ContentPack pack : environment.packs()) {
            for (ContentResource resource : pack.resources()) {
                if (field.equals("template") != "template".equals(resource.contentType())) {
                    continue;
                }
                int distance = levenshtein(target, resource.id().value());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = resource.id().toString();
                }
            }
        }
        return best;
    }

    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
