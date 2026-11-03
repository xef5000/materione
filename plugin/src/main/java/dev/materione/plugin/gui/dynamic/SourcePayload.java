package dev.materione.plugin.gui.dynamic;

import dev.materione.api.logic.ExpressionCompiler;
import dev.materione.api.logic.ParamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parameters of an item or list source as authored (logic.md §4.4, §6.7): a mapping is the parameters as they
 * are, and a scalar is the source's shorthand parameter ({@code player_slot: main_hand} reads as
 * {@code {slot: main_hand}}). Shared by the item sources of {@link ItemLayerCompiler} and the list sources of
 * the list compiler, so the two read a payload the same way.
 */
public final class SourcePayload {

    private SourcePayload() {}

    /**
     * The reader over a source's parameters.
     *
     * @param shorthand the parameter a scalar is read under, or null when the source takes only a mapping
     * @param payload the authored value: a mapping, a scalar, or null for a source with no parameters
     * @throws IllegalArgumentException when a scalar is given to a source with no shorthand, or a name is not text
     */
    public static ParamReader params(String shorthand, Object payload, ExpressionCompiler expressions) {
        if (payload == null) {
            return ParamReader.of(Map.of(), expressions);
        }
        if (payload instanceof Map<?, ?> mapping) {
            var params = new LinkedHashMap<String, Object>();
            mapping.forEach((key, value) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("parameter names are text");
                }
                params.put(name, value);
            });
            return ParamReader.of(params, expressions);
        }
        if (shorthand == null) {
            throw new IllegalArgumentException("takes a mapping of parameters, not a single value");
        }
        // a number or a boolean as written (hdb: 12345) is its text, which is how a source reads it
        Object text = payload instanceof String ? payload : String.valueOf(payload);
        return ParamReader.of(Map.of(shorthand, text), expressions);
    }
}
