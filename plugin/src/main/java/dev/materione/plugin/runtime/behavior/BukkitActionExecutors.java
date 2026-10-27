package dev.materione.plugin.runtime.behavior;

import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.expr.Expression;
import dev.materione.behavior.expr.ExpressionParser;
import dev.materione.behavior.interp.Template;
import dev.materione.behavior.rule.ActionSpec;
import dev.materione.behavior.runtime.ActionExecutor;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.platform.text.MaterioneText;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The M2 core action vocabulary (runtime-systems §2.1), implemented on the
 * platform: {@code run_command}, {@code message}, {@code sound}, {@code
 * potion}, {@code cancel}, {@code set_var}. Each declares sync-safe vs
 * must-defer; string params go through ${} interpolation and the PAPI
 * resolver. A throwing executor degrades to a rate-limited diagnostic in
 * the engine — never a broken chain.
 */
public final class BukkitActionExecutors {

    private BukkitActionExecutors() {}

    /** Registers the built-in action executors onto the engine. */
    public static void registerAll(dev.materione.behavior.runtime.RuleEngine engine) {
        engine.register(new RunCommand());
        engine.register(new Message());
        engine.register(new Sound());
        engine.register(new Potion());
        engine.register(new Cancel());
        engine.register(new SetVar());
    }

    private static String interpolate(String template, BehaviorEvent event) {
        return PlaceholderResolver.apply(
                event.anchor() instanceof Player player ? player : null,
                Template.render(template, event.context()));
    }

    private static Player playerOf(BehaviorEvent event) {
        if (event.anchor() instanceof Player player) {
            return player;
        }
        throw new IllegalStateException("event has no player");
    }

    /** {@code run_command: "say hi"} or {run_command: {command, as: console|player}} — must-defer. */
    private static final class RunCommand implements ActionExecutor {
        @Override
        public String type() {
            return "run_command";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Run command", "Runs a server command; %player% and ${...} are filled in",
                    List.of(
                            new ParameterSchema("command", ParameterType.STRING, true, null, "Command without the leading slash", null, null),
                            new ParameterSchema("as", ParameterType.ENUM, false, "console", "Who runs it", List.of("console", "player"), null)),
                    "2.0.0");
        }

        @Override
        public String shorthand() {
            return "command";
        }

        @Override
        public boolean syncSafe() {
            return false; // commands may touch any region — defer through the gateway
        }

        @Override
        @SuppressWarnings("unchecked")
        public void execute(ActionSpec action, BehaviorEvent event) {
            Object raw = action.scalarParam() != null ? action.scalarParam() : action.param("command");
            if (!(raw instanceof String command) || command.isBlank()) {
                throw new IllegalArgumentException("run_command needs a command string");
            }
            String resolved = interpolate(command, event);
            String mode = action.param("as") instanceof String as && !as.isBlank() ? as : "console";
            if (mode.equals("player")) {
                boolean found = playerOf(event).performCommand(resolved.startsWith("/") ? resolved.substring(1) : resolved);
                if (!found) {
                    throw new IllegalStateException("player has no command '" + resolved + "'");
                }
            } else if (mode.equals("op")) {
                Player player = playerOf(event);
                boolean previousOp = player.isOp();
                try {
                    player.setOp(true);
                    player.performCommand(resolved.startsWith("/") ? resolved.substring(1) : resolved);
                } finally {
                    player.setOp(previousOp);
                }
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved.startsWith("/")
                        ? resolved.substring(1)
                        : resolved);
            }
        }
    }

    /** {@code message: "&aHi"} or {message: {text, target: player|actionbar|broadcast}}. */
    private static final class Message implements ActionExecutor {
        @Override
        public String type() {
            return "message";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Send message", "Shows text to the player, on the action bar, or to everyone",
                    List.of(
                            new ParameterSchema("text", ParameterType.STRING, true, null, "Message text; formatting and & colour codes allowed", null, null),
                            new ParameterSchema("target", ParameterType.ENUM, false, "player", "Where the text appears", List.of("player", "actionbar", "broadcast"), null)),
                    "2.0.0");
        }

        @Override
        public String shorthand() {
            return "text";
        }

        @Override
        public boolean syncSafe() {
            return true;
        }

        @Override
        public void execute(ActionSpec action, BehaviorEvent event) {
            Object raw = action.scalarParam() != null ? action.scalarParam() : action.param("text");
            if (!(raw instanceof String text)) {
                throw new IllegalArgumentException("message needs text");
            }
            String target = action.param("target") instanceof String t && !t.isBlank() ? t : "player";
            String rendered = interpolate(text, event);
            switch (target) {
                case "actionbar" -> playerOf(event).sendActionBar(MaterioneText.parse(rendered));
                case "broadcast" -> Bukkit.broadcast(MaterioneText.parse(rendered));
                default -> playerOf(event).sendMessage(MaterioneText.parse(rendered));
            }
        }
    }

    /** {@code sound: "minecraft:block.note_block.pling"} or {sound, volume, pitch}. */
    private static final class Sound implements ActionExecutor {
        @Override
        public String type() {
            return "sound";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Play sound", "Plays a sound at the player",
                    List.of(
                            new ParameterSchema("sound", ParameterType.STRING, true, null, "Sound key, e.g. minecraft:block.note_block.pling", null, null),
                            new ParameterSchema("volume", ParameterType.DECIMAL, false, "1", "Volume", null, null),
                            new ParameterSchema("pitch", ParameterType.DECIMAL, false, "1", "Pitch", null, null)),
                    "2.0.0");
        }

        @Override
        public String shorthand() {
            return "sound";
        }

        @Override
        public boolean syncSafe() {
            return true;
        }

        @Override
        public void execute(ActionSpec action, BehaviorEvent event) {
            Object raw = action.scalarParam() != null ? action.scalarParam() : action.param("sound");
            if (!(raw instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("sound needs a sound key");
            }
            float volume = (float) doubleValue(action.param("volume"), 1d);
            float pitch = (float) doubleValue(action.param("pitch"), 1d);
            Player player = playerOf(event);
            player.playSound(player.getLocation(), interpolate(name, event), volume, pitch);
        }
    }

    /** {@code potion: {type: speed, duration: 200, amplifier: 1}} — duration in ticks. */
    private static final class Potion implements ActionExecutor {
        @Override
        public String type() {
            return "potion";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Give potion effect", "Applies a potion effect to the player",
                    List.of(
                            new ParameterSchema("type", ParameterType.STRING, true, null, "Effect type, e.g. speed", null, null),
                            new ParameterSchema("duration", ParameterType.INTEGER, false, "200", "Duration in ticks", null, null),
                            new ParameterSchema("amplifier", ParameterType.INTEGER, false, "0", "Amplifier; 0 is level I", null, null)),
                    "2.0.0");
        }

        @Override
        public boolean syncSafe() {
            return true;
        }

        @Override
        public void execute(ActionSpec action, BehaviorEvent event) throws IllegalArgumentException {
            if (!(action.param("type") instanceof String typeName) || typeName.isBlank()) {
                throw new IllegalArgumentException("potion needs a 'type'");
            }
            var key = org.bukkit.NamespacedKey.minecraft(typeName.toLowerCase(java.util.Locale.ROOT));
            org.bukkit.potion.PotionEffectType effect = org.bukkit.Registry.EFFECT.get(key);
            if (effect == null) {
                throw new IllegalArgumentException("unknown potion effect '" + typeName + "'");
            }
            int duration = (int) doubleValue(action.param("duration"), 200d);
            int amplifier = (int) doubleValue(action.param("amplifier"), 0d);
            boolean added = playerOf(event).addPotionEffect(
                    new org.bukkit.potion.PotionEffect(effect, Math.max(1, duration), amplifier));
            if (!added) {
                throw new IllegalStateException("potion effect '" + typeName + "' was not applied");
            }
        }
    }

    /** {@code cancel: true} — cancels the underlying Bukkit event after the chain completes. */
    private static final class Cancel implements ActionExecutor {
        @Override
        public String type() {
            return "cancel";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Cancel the event", "Cancels the underlying game event after the actions run",
                    List.of(
                            new ParameterSchema("value", ParameterType.BOOLEAN, false, "true", "Cancel when true", null, null)),
                    "2.0.0");
        }

        @Override
        public String shorthand() {
            return "value";
        }

        @Override
        public boolean syncSafe() {
            return true;
        }

        @Override
        public void execute(ActionSpec action, BehaviorEvent event) {
            event.requestCancel();
        }
    }

    /**
     * {@code set_var: {name: count, value: "${vars.count + 1}"}} — transient
     * for v1: variables live for the event chain (persistent per-player vars
     * arrive with later persistence work).
     */
    private static final class SetVar implements ActionExecutor {
        @Override
        public String type() {
            return "set_var";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Set variable", "Stores a value for the rest of this event chain",
                    List.of(
                            new ParameterSchema("name", ParameterType.STRING, true, null, "Variable name", null, null),
                            new ParameterSchema("value", ParameterType.STRING, false, null, "Value or ${expression}", null, null)),
                    "2.0.0");
        }

        @Override
        public boolean syncSafe() {
            return true;
        }

        @Override
        public void execute(ActionSpec action, BehaviorEvent event) {
            if (!(action.param("name") instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("set_var needs a 'name'");
            }
            Map<String, Object> root = event.context().root();
            if (!(root.get("vars") instanceof Map<?, ?>)) {
                root.put("vars", new java.util.LinkedHashMap<String, Object>());
            }
            @SuppressWarnings("unchecked")
            var vars = (Map<String, Object>) root.get("vars");
            Object value = action.param("value");
            if (value == null) {
                value = action.scalarParam();
            }
            if (value instanceof String expressionText && expressionText.contains("${")) {
                value = interpolate(expressionText, event);
            } else if (value instanceof String expressionText && looksLikeExpression(expressionText)) {
                Expression parsed = ExpressionParser.parse(expressionText);
                value = parsed.eval(new EvaluationContext(root));
            }
            vars.put(name, value);
        }

        private static boolean looksLikeExpression(String text) {
            // arithmetic/logic intent: digits+operators or dotted paths with operators
            return text.matches(".*[+\\-*/%<>!]|.*&&|.*\\|\\||.*(==|!=).*");
        }
    }

    private static double doubleValue(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
