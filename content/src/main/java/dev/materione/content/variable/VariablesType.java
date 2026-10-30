package dev.materione.content.variable;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.Templates;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code variable} content type (logic.md §3.2): a declared persistent variable, read from
 * {@code variables/*.yml}. One file may declare several, one per top-level key, like any content type:
 *
 * <pre>{@code
 * coins:         { scope: player, type: number, default: 0, description: "Shop coins" }
 * daily_claimed: { scope: player, type: boolean, default: false }
 * season:        { scope: server, type: string, default: "spring" }
 * }</pre>
 *
 * <p>The declaration is pack-namespaced by its resource id ({@code shop:coins}). It produces no pack
 * output: the declaration lives in the shadow (the {@code RuntimeVariables} view), and the value lives
 * in the store, so {@link #compile} does nothing. A definition whose scope, type or default does not
 * fit is quarantined by the pipeline, like any other malformed resource.
 */
public final class VariablesType implements ContentType {

    @Override
    public String name() {
        return "variable";
    }

    @Override
    public String directory() {
        return "variables";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Variable",
                "A declared persistent variable: stored per player or on the server, typed, with a default",
                List.of(
                        new ParameterSchema("scope", ParameterType.STRING, true, null,
                                "Where the value is stored: player (per player) or server (one for the server)",
                                List.of(VariableScope.PLAYER.id(), VariableScope.SERVER.id()), null, null),
                        new ParameterSchema("type", ParameterType.STRING, true, null,
                                "The value's type: number, string or boolean",
                                List.of(VariableType.NUMBER.id(), VariableType.STRING.id(), VariableType.BOOLEAN.id()),
                                null, null),
                        new ParameterSchema("default", ParameterType.RAW, false, null,
                                "Value read while nothing is stored (of the declared type; 0, empty text or false when omitted)",
                                null, null),
                        new ParameterSchema("description", ParameterType.STRING, false, null,
                                "Note shown in the studio and by /materione var list", null, null)),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty();
        }
        Optional<VariableScope> scope = VariableScope.of(Templates.text(merged, "scope"));
        Optional<VariableType> type = VariableType.of(Templates.text(merged, "type"));
        if (scope.isEmpty() || type.isEmpty()) {
            return Optional.empty();
        }
        Object description = merged.get("description");
        try {
            return Optional.of(new VariableDeclaration(id, scope.get(), type.get(), merged.get("default"),
                    description instanceof String text ? text : ""));
        } catch (IllegalArgumentException mismatched) {
            return Optional.empty(); // a default that does not fit the type: the resource is quarantined
        }
    }

    @Override
    public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        // no pack output: the declaration is runtime state in the shadow, the value is in the store
    }
}
