package dev.materione.plugin.gui.list;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.ListSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code variable}: the values of a declared persistent string variable, read as a separated list (logic.md §3.2,
 * §6.7). The variable types are number, string and boolean, so a list is a string whose pieces are separated by
 * {@code separator} (a comma by default); each non-blank piece is an entry with a {@code value}. Shorthand:
 * {@code variable: shop:favourites}. The variable is a player or server variable, declared by its pack; a bare name
 * means the pack of the resource that holds the list.
 */
public final class VariableListSource implements ListSource<VariableListSource.Params> {

    /** The parsed parameters: the variable and the separator between its pieces. */
    public record Params(NamespacedId variable, String separator) {}

    @Override
    public String id() {
        return "variable";
    }

    @Override
    public ListSourceDescription describe() {
        return new ListSourceDescription(
                new SelfDescription("Variable list",
                        "The pieces of a declared player or server string variable, separated by a separator",
                        List.of(
                                new ParameterSchema("var", ParameterType.STRING, true, null,
                                        "The declared variable, pack:key", null, null),
                                new ParameterSchema("separator", ParameterType.STRING, false, ",",
                                        "What separates the pieces", null, null)),
                        "1.0.0"),
                "var",
                List.of(ContextVariable.of("entry.value", ParameterType.STRING, "One piece of the variable's text")),
                false);
    }

    @Override
    public Params parse(ParamReader params) {
        String text = params.text("var");
        NamespacedId variable;
        if (text.contains(":")) {
            variable = NamespacedId.parse(text);
        } else {
            String pack = params.environment().pack();
            if (pack == null) {
                throw new IllegalArgumentException("var '" + text + "' needs its pack: write pack:key");
            }
            variable = NamespacedId.of(pack, text);
        }
        return new Params(variable, params.text("separator", ","));
    }

    @Override
    public List<Map<String, Object>> entries(Params parameters, LogicContext context) {
        VariableDeclaration declaration = context.variables().declaration(parameters.variable())
                .orElseThrow(() -> new IllegalStateException(parameters.variable() + " is not a declared variable"));
        if (declaration.type() != VariableType.STRING) {
            throw new IllegalStateException(parameters.variable() + " is not a string variable");
        }
        if (declaration.scope() != VariableScope.PLAYER && declaration.scope() != VariableScope.SERVER) {
            throw new IllegalStateException(parameters.variable() + " is not a player or server variable");
        }
        String value = context.variables().stored(declaration)
                .map(String::valueOf)
                .orElse(String.valueOf(declaration.defaultValue()));
        var entries = new ArrayList<Map<String, Object>>();
        if (value.isEmpty()) {
            return entries;
        }
        for (String piece : value.split(Pattern.quote(parameters.separator()), -1)) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                entries.add(Map.of("value", trimmed));
            }
        }
        return entries;
    }
}
