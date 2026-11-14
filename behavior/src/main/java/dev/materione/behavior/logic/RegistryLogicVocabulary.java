package dev.materione.behavior.logic;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.core.pipeline.validate.LogicVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link LogicVocabulary} over the live action and condition registries: which parameters name content, and of
 * what kind, is read from each type's self-description, never from a list kept here. A parameter names content when
 * it is drawn with the {@code item} widget, or is a {@code REFERENCE} that declares its kind
 * ({@code ParameterSchema.EditorMetadata#referenceKind}). A parameter that names content and says neither is a
 * self-description gap, and is not found (listed in codemode.md section 10).
 */
public final class RegistryLogicVocabulary implements LogicVocabulary {

    private final LogicLookup lookup;

    public RegistryLogicVocabulary(LogicLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<Entry> entry(String authoredKey) {
        try {
            var action = lookup.action(authoredKey);
            if (action.isPresent()) {
                var description = action.get().describe();
                return Optional.of(new Entry(true, kinds(description.self().parameters()), description.shorthand()));
            }
            var condition = lookup.condition(authoredKey);
            if (condition.isPresent()) {
                var description = condition.get().describe();
                return Optional.of(new Entry(false, kinds(description.self().parameters()), description.shorthand()));
            }
        } catch (RuntimeException broken) {
            // a broken description is "not an action": the compiler reports it, the reference walk skips it
        }
        return Optional.empty();
    }

    private static Map<String, String> kinds(List<ParameterSchema> parameters) {
        var out = new LinkedHashMap<String, String>();
        for (ParameterSchema parameter : parameters) {
            String kind = kind(parameter);
            if (kind != null) {
                out.put(parameter.id(), kind);
            }
        }
        return out;
    }

    private static String kind(ParameterSchema parameter) {
        var editor = parameter.editor();
        if ("item".equals(editor.widget())) {
            return "item";
        }
        if (parameter.type() == ParameterType.REFERENCE && editor.referenceKind() != null
                && !editor.referenceKind().isBlank()) {
            return editor.referenceKind();
        }
        return null;
    }
}
