package dev.materione.behavior.rule;

import java.util.Map;
import java.util.Objects;

/**
 * One parsed action invocation: the action type, its raw params (a scalar
 * shorthand or a map — executors interpret), and the two universal
 * properties every action carries ({@code chance:} 0..1, {@code delay:}
 * ticks) — the shape Nexo's action DSL proved out over years of use.
 */
public record ActionSpec(String type, Map<String, Object> params, double chance, long delayTicks) {

    public static final String CHANCE_KEY = "chance";
    public static final String DELAY_KEY = "delay";

    public ActionSpec {
        params = Map.copyOf(params);
    }

    /** The single scalar param ({@code run_command: "say hi"}), if the author used the shorthand. */
    public Object scalarParam() {
        return params.get("value");
    }

    /** A named param from map form. */
    public Object param(String key) {
        return params.get(key);
    }

    public String stringParamOrScalar(String key, String fallback) {
        Object value = params.containsKey(key) ? params.get(key) : scalarParam();
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ActionSpec other
                && type.equals(other.type)
                && chance == other.chance
                && delayTicks == other.delayTicks
                && params.equals(other.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, params, chance, delayTicks);
    }

    @Override
    public String toString() {
        var out = new StringBuilder(type).append(params);
        if (chance < 1) {
            out.append(" chance=").append(chance);
        }
        if (delayTicks > 0) {
            out.append(" delay=").append(delayTicks);
        }
        return out.toString();
    }
}
