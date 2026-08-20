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
        return problems;
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
