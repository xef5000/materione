package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.ParamReader;
import dev.materione.behavior.logic.EngineExpressions;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared reads for the condition tests: the authored-parameter reader and a parameter map builder. */
final class ConditionFixtures {

    private ConditionFixtures() {}

    /** A reader over authored parameters, compiling templates with the engine's expression compiler. */
    static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE);
    }

    /** Key/value pairs as an authored parameter map, in order. */
    static Map<String, Object> params(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
