package dev.materione.core.pipeline.validate;

import dev.materione.core.pipeline.ContentResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the static references in a resource's {@code behavior:} and {@code flows:} by walking the authored tree and
 * asking a {@link LogicVocabulary} which keys are actions or conditions. The walk is structural and tolerant: it
 * needs no parse, so a rule the compiler would skip is still searched, and a key that is not an action or a
 * condition (a branch, a rule option) is walked through. The parameters of an action are not walked into (they
 * hold values, not steps); those of a condition are, because {@code all}, {@code any} and {@code not} hold
 * conditions.
 *
 * <p>Only whole, literal strings are references. A value with a template ({@code ${...}}) or a placeholder
 * ({@code %...%}) is an expression, resolved when the rule runs, and is not reported here.
 */
public final class StaticReferenceWalker implements StaticReferences {

    private static final int MAX_DEPTH = 64;

    private final LogicVocabulary vocabulary;

    public StaticReferenceWalker(LogicVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    @Override
    public List<Ref> of(ContentResource resource) {
        if (!(resource.document().data().get(resource.id().value()) instanceof Map<?, ?> definition)) {
            return List.of();
        }
        List<Ref> out = new ArrayList<>();
        for (String section : List.of("behavior", "flows")) {
            Object value = definition.get(section);
            if (value != null) {
                walk(value, new ArrayList<>(List.of(section)), out, 0);
            }
        }
        return out;
    }

    private void walk(Object node, List<String> path, List<Ref> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                path.add(Integer.toString(index));
                walk(list.get(index), path, out, depth + 1);
                path.remove(path.size() - 1);
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                path.add(key);
                Optional<LogicVocabulary.Entry> known = vocabulary.entry(key);
                if (known.isPresent()) {
                    collect(key, known.get(), entry.getValue(), path, out);
                }
                if (known.isEmpty() || !known.get().action()) {
                    walk(entry.getValue(), path, out, depth + 1);
                }
                path.remove(path.size() - 1);
            }
        }
    }

    private static void collect(
            String origin, LogicVocabulary.Entry entry, Object value, List<String> path, List<Ref> out) {
        if (value instanceof String scalar && entry.shorthand() != null) {
            reference(origin, entry.references().get(entry.shorthand()), scalar, path, out);
        } else if (value instanceof Map<?, ?> params) {
            for (Map.Entry<String, String> parameter : entry.references().entrySet()) {
                if (params.get(parameter.getKey()) instanceof String scalar) {
                    List<String> at = new ArrayList<>(path);
                    at.add(parameter.getKey());
                    reference(origin, parameter.getValue(), scalar, at, out);
                }
            }
        }
    }

    private static void reference(String origin, String kind, String written, List<String> path, List<Ref> out) {
        if (kind == null || written.isBlank() || written.contains("${") || written.contains("%")) {
            return;
        }
        out.add(new Ref(kind, written.trim(), List.copyOf(path), origin));
    }
}
