package dev.materione.api.logic;

import dev.materione.api.logic.variable.CompileEnvironment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The authored parameters of one step, read with strict, non-coercing, typed accessors (logic.md
 * §4.1 "parse once, run many"). {@link ActionType#parse} and {@link ConditionType#parse} take one
 * and return an immutable parameter object; a bad value throws
 * {@link IllegalArgumentException}, the engine prefixes it with the step's id and publishes it as a
 * diagnostic on that step alone.
 *
 * <p>The engine normalises the author's two spellings before a type sees them: a scalar shorthand
 * ({@code message: "hi"}) arrives as a one-entry map under the type's declared
 * {@code shorthand} parameter, so a type only ever reads named parameters.
 *
 * <p>Reads never coerce: a quoted {@code "5"} is not an integer, {@code 1} is not a boolean. A
 * missing optional value takes the caller's fallback; a present value of the wrong type is an
 * error, never silently replaced. Values are deeply immutable copies of the authored YAML tree.
 *
 * <p>Error messages are phrased as {@code <key> must be …} so they read well after the engine adds
 * the step's name.
 */
public final class ParamReader {

    private final Map<String, Object> params;
    private final ExpressionCompiler compiler;
    private final CompileEnvironment environment;

    private ParamReader(Map<String, Object> params, ExpressionCompiler compiler, CompileEnvironment environment) {
        this.params = params;
        this.compiler = compiler;
        this.environment = environment;
    }

    /**
     * A reader over {@code params} (strings, numbers, booleans, lists and maps, the shapes YAML
     * produces).
     *
     * @param compiler where templates and expressions are compiled; the engine passes its own
     * @throws IllegalArgumentException when a value has an unsupported type
     */
    public static ParamReader of(Map<String, ?> params, ExpressionCompiler compiler) {
        return of(params, compiler, CompileEnvironment.NONE);
    }

    /**
     * A reader over {@code params} that also knows the resource the step belongs to (its pack and
     * declared variables, logic.md §3.2). The engine passes its own; a type reads it through
     * {@link #environment()} to check a reference at load.
     */
    public static ParamReader of(Map<String, ?> params, ExpressionCompiler compiler, CompileEnvironment environment) {
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(environment, "environment");
        return new ParamReader(immutableMap(params), compiler, environment);
    }

    /** The resource the step belongs to: its pack and the variables it can see; never null. */
    public CompileEnvironment environment() {
        return environment;
    }

    /** A reader with literal-only templates ({@link ExpressionCompiler#LITERAL}); for unit tests. */
    public static ParamReader of(Map<String, ?> params) {
        return of(params, ExpressionCompiler.LITERAL);
    }

    // --- presence ---------------------------------------------------------------

    /** True when the author wrote {@code key} (even with a null value). */
    public boolean has(String key) {
        return params.containsKey(key);
    }

    /** The keys the author wrote, in authored order. */
    public Set<String> keys() {
        return params.keySet();
    }

    /** The untouched value, or empty when absent or null. */
    public Optional<Object> raw(String key) {
        return Optional.ofNullable(params.get(key));
    }

    // --- scalars ----------------------------------------------------------------

    /** A required non-blank string. */
    public String text(String key) {
        return string(key, true);
    }

    /** An optional non-blank string; {@code fallback} when absent. */
    public String text(String key, String fallback) {
        return isAbsent(key) ? fallback : string(key, true);
    }

    /** An optional non-blank string. */
    public Optional<String> optionalText(String key) {
        return isAbsent(key) ? Optional.empty() : Optional.of(string(key, true));
    }

    /** A required integer in {@code min..max}. */
    public int requiredInteger(String key, int min, int max) {
        if (isAbsent(key)) {
            throw missing(key);
        }
        return integerValue(key, params.get(key), min, max);
    }

    /** An optional integer in {@code min..max}; {@code fallback} when absent. */
    public int integer(String key, int fallback, int min, int max) {
        return isAbsent(key) ? fallback : integerValue(key, params.get(key), min, max);
    }

    /** An optional finite number in {@code min..max}; {@code fallback} when absent. */
    public double decimal(String key, double fallback, double min, double max) {
        if (isAbsent(key)) {
            return fallback;
        }
        if (params.get(key) instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value >= min && value <= max) {
                return value;
            }
        }
        throw new IllegalArgumentException(key + " must be a finite number in " + min + ".." + max);
    }

    /** An optional boolean; {@code fallback} when absent. */
    public boolean bool(String key, boolean fallback) {
        if (isAbsent(key)) {
            return fallback;
        }
        if (params.get(key) instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(key + " must be true or false");
    }

    /**
     * An optional choice among {@code allowed}, matched ignoring case and returned in the allowed
     * list's spelling; {@code fallback} when absent.
     */
    public String choice(String key, String fallback, Collection<String> allowed) {
        if (isAbsent(key)) {
            return fallback;
        }
        String given = string(key, true);
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(given)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(key + " must be one of " + String.join(", ", allowed));
    }

    /** An optional enum constant, matched ignoring case against the constant names. */
    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E fallback) {
        if (isAbsent(key)) {
            return fallback;
        }
        String given = string(key, true).toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(given)) {
                return constant;
            }
        }
        var names = new ArrayList<String>();
        for (E constant : type.getEnumConstants()) {
            names.add(constant.name().toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException(key + " must be one of " + String.join(", ", names));
    }

    // --- templates and expressions ----------------------------------------------

    /** A required template (a string that may hold {@code ${...}} and {@code %token%}). */
    public TemplateText template(String key) {
        return compileTemplate(key, string(key, true));
    }

    /** An optional template; compiled from {@code fallbackSource} when absent. */
    public TemplateText template(String key, String fallbackSource) {
        return isAbsent(key) ? compileTemplate(key, fallbackSource) : template(key);
    }

    /** An optional template. */
    public Optional<TemplateText> optionalTemplate(String key) {
        return isAbsent(key) ? Optional.empty() : Optional.of(template(key));
    }

    /** A required bare expression (no {@code ${}} wrapper) whose value is typed, not text. */
    public ValueExpression expression(String key) {
        String source = string(key, true);
        try {
            return compiler.expression(source);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage(), e);
        }
    }

    // --- collections ------------------------------------------------------------

    /** A list of non-blank strings; empty when absent. */
    public List<String> strings(String key) {
        if (isAbsent(key)) {
            return List.of();
        }
        if (!(params.get(key) instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list of strings");
        }
        var result = new ArrayList<String>();
        for (Object entry : list) {
            if (!(entry instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(key + " must contain non-blank strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    /** A nested mapping read with the same accessors. */
    public ParamReader section(String key) {
        if (isAbsent(key)) {
            throw missing(key);
        }
        if (params.get(key) instanceof Map<?, ?> map) {
            return new ParamReader(stringKeys(map), compiler, environment);
        }
        throw new IllegalArgumentException(key + " must be a mapping");
    }

    /** A list of nested mappings; empty when absent. */
    public List<ParamReader> sections(String key) {
        if (isAbsent(key)) {
            return List.of();
        }
        if (!(params.get(key) instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list of mappings");
        }
        var result = new ArrayList<ParamReader>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(key + " must contain mappings");
            }
            result.add(new ParamReader(stringKeys(map), compiler, environment));
        }
        return List.copyOf(result);
    }

    // --- internals --------------------------------------------------------------

    private boolean isAbsent(String key) {
        return params.get(key) == null;
    }

    private String string(String key, boolean nonBlank) {
        Object value = params.get(key);
        if (value == null) {
            throw missing(key);
        }
        if (value instanceof String text && (!nonBlank || !text.isBlank())) {
            return text;
        }
        throw new IllegalArgumentException(key + " must be a non-blank string");
    }

    private TemplateText compileTemplate(String key, String source) {
        try {
            return compiler.template(source);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage(), e);
        }
    }

    private static int integerValue(String key, Object value, int min, int max) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal) && decimal == Math.rint(decimal) && decimal >= min && decimal <= max) {
                return number.intValue();
            }
        }
        throw new IllegalArgumentException(key + " must be an integer in " + min + ".." + max);
    }

    private static IllegalArgumentException missing(String key) {
        return new IllegalArgumentException(key + " is required");
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(Objects.requireNonNull(key, "parameter key"), immutable(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("parameter names must be strings");
            }
            result.put(text, value);
        });
        return result;
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableMap(stringKeys(map));
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>();
            list.forEach(entry -> result.add(immutable(entry)));
            return Collections.unmodifiableList(result);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("unsupported parameter value: " + value.getClass().getSimpleName());
    }
}
