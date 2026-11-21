package dev.materione.api.logic;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The typed value of an action's {@code target:} (logic.md §39): who or what the action acts on. It is parsed once,
 * at load, by {@link #read} and shared by every targetable action, so no action parses a target itself and every
 * action spells it the same way (the one value shape of the universality tests, master §3.1).
 *
 * <table>
 *   <caption>The five forms</caption>
 *   <tr><td>{@code self} (the default)</td><td>{@link Self}: the run's own player; the action runs as it always did</td></tr>
 *   <tr><td>{@code event.target}</td><td>{@link Event}: the entity the trigger carries ({@code entity.hit}, {@code entity.kill})</td></tr>
 *   <tr><td>{@code {arg: name}}</td><td>{@link Arg}: an online player named by a command or GUI argument</td></tr>
 *   <tr><td>{@code {player: "${...}"}}</td><td>{@link Player}: an online player named by a template (a name or a uuid)</td></tr>
 *   <tr><td>{@code {nearby: {radius, type, limit}}}</td><td>{@link Nearby}: up to {@code limit} entities within
 *       {@code radius} blocks of the run's player, nearest first</td></tr>
 * </table>
 *
 * <p>A spec is a description, not a subject: resolving it against a running context is the platform's
 * {@link TargetResolver}. A value that is none of the forms, or a form with a bad parameter, is rejected at load
 * with {@link IllegalArgumentException}, like any action parameter.
 */
public sealed interface TargetSpec {

    /** The parameter key an action's target is written under. */
    String KEY = "target";

    /** The largest search radius of a {@link Nearby}, in blocks. */
    double MAX_RADIUS = 64;

    /** The most subjects a {@link Nearby} selects. */
    int MAX_LIMIT = 16;

    /** A short phrase naming the spec, for diagnostics and the trace ({@code argument to}, {@code nearby 8 zombie}). */
    String describe();

    /** The run's own player: the action behaves exactly as it does without a target. */
    record Self() implements TargetSpec {
        @Override
        public String describe() {
            return "self";
        }
    }

    /** The entity the trigger carries ({@code event.target}). */
    record Event() implements TargetSpec {
        @Override
        public String describe() {
            return "event.target";
        }
    }

    /**
     * A player named by an argument of the command or GUI the run belongs to.
     *
     * @param name the argument's name ({@code to} in {@code /pay <to> <amount>})
     */
    record Arg(String name) implements TargetSpec {
        @Override
        public String describe() {
            return "argument " + name;
        }
    }

    /**
     * An online player named by a template that renders to a name or a uuid.
     *
     * @param name the template
     */
    record Player(TemplateText name) implements TargetSpec {
        @Override
        public String describe() {
            return "player " + name.source();
        }
    }

    /**
     * A bounded selector around the run's player: the entities within {@code radius} blocks, nearest first, never
     * the run's player itself.
     *
     * @param radius the search radius, in blocks, up to {@link #MAX_RADIUS}
     * @param type an entity type key ({@code zombie}, {@code player}) the entities must have, or {@code null} for
     *        every kind the action admits
     * @param limit the most entities selected, 1 to {@link #MAX_LIMIT}
     */
    record Nearby(double radius, String type, int limit) implements TargetSpec {
        @Override
        public String describe() {
            return "nearby " + trim(radius) + (type == null ? "" : " " + type) + " x" + limit;
        }

        private static String trim(double value) {
            return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        }
    }

    /**
     * Reads the step's {@code target:}.
     *
     * @return the parsed target, or empty when the step wrote none (which means {@link Self})
     * @throws IllegalArgumentException when the value is none of the five forms or a form's parameter is bad; the
     *         message reads after the engine's "action 'x' rejected:" prefix
     */
    static Optional<TargetSpec> read(ParamReader params) {
        Optional<Object> raw = params.raw(KEY);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (raw.get() instanceof String word) {
            return Optional.of(switch (word) {
                case "self" -> new Self();
                case "event.target" -> new Event();
                default -> throw new IllegalArgumentException(KEY + " '" + word + "' is not a target; write self, "
                        + "event.target, or a mapping with arg, player or nearby");
            });
        }
        if (!(raw.get() instanceof java.util.Map<?, ?>)) {
            throw new IllegalArgumentException(KEY + " must be self, event.target, or a mapping with arg, player or nearby");
        }
        ParamReader form = params.section(KEY);
        Set<String> keys = form.keys();
        if (keys.size() != 1) {
            throw new IllegalArgumentException(KEY + " must name exactly one of arg, player or nearby, not "
                    + (keys.isEmpty() ? "none" : String.join(", ", keys)));
        }
        String key = keys.iterator().next();
        return Optional.of(switch (key) {
            case "arg" -> arg(form);
            case "player" -> new Player(form.template("player"));
            case "nearby" -> nearby(form.section("nearby"));
            default -> throw new IllegalArgumentException(KEY + "." + key
                    + " is not a target form; use arg, player or nearby");
        });
    }

    private static TargetSpec arg(ParamReader form) {
        String name = form.text("arg");
        if (!ARGUMENT.matcher(name).matches()) {
            throw new IllegalArgumentException("target.arg must be an argument name (letters, digits, underscore)");
        }
        return new Arg(name);
    }

    private static TargetSpec nearby(ParamReader form) {
        for (String key : form.keys()) {
            if (!List.of("radius", "type", "limit").contains(key)) {
                throw new IllegalArgumentException("target.nearby." + key + " is not a parameter; use radius, type, limit");
            }
        }
        double radius = form.decimal("radius", Double.NaN, 0.5, MAX_RADIUS);
        if (Double.isNaN(radius)) {
            throw new IllegalArgumentException("target.nearby.radius is required (0.5 to " + (int) MAX_RADIUS + " blocks)");
        }
        String type = form.optionalText("type").map(TargetSpec::entityKey).orElse(null);
        return new Nearby(radius, type, form.integer("limit", 1, 1, MAX_LIMIT));
    }

    private static String entityKey(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        key = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        if (!ENTITY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("target.nearby.type '" + raw + "' is not an entity type key");
        }
        return key;
    }

    /** What an argument name looks like. */
    Pattern ARGUMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** What an entity type key looks like once the namespace is dropped. */
    Pattern ENTITY_KEY = Pattern.compile("[a-z0-9_./-]+");
}
