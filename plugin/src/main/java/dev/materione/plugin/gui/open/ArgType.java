package dev.materione.plugin.gui.open;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The argument types an {@code open.args} entry may declare (logic.md §6.1): {@code string}, {@code int},
 * {@code number}, {@code player}, {@code boolean} and {@code choice}. The author's word is the name; the
 * parse turns the text a command or the API passed into the argument's canonical text, or says why it
 * cannot. Every parse is a pure function of its inputs, so a bad value is a usage message, never a crash.
 */
public enum ArgType {
    STRING("string"),
    INT("int"),
    NUMBER("number"),
    PLAYER("player"),
    BOOLEAN("boolean"),
    CHOICE("choice");

    private final String word;

    ArgType(String word) {
        this.word = word;
    }

    /** The author's name for the type, as written under {@code type:}. */
    public String word() {
        return word;
    }

    /** The type named {@code word}, if there is one. */
    public static Optional<ArgType> of(String word) {
        for (ArgType type : values()) {
            if (type.word.equals(word)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads {@code raw} as this type.
     *
     * @param choices the allowed values of a {@code choice}; ignored by the other types
     * @param players the online players a {@code player} resolves against
     * @return the canonical text, or the reason it is not valid
     */
    public Typed parse(String raw, List<String> choices, PlayerNames players) {
        return switch (this) {
            case STRING -> raw.isEmpty() ? Typed.refused("is empty") : Typed.of(raw);
            case INT -> wholeNumber(raw);
            case NUMBER -> number(raw);
            case PLAYER -> players.online(raw).map(Typed::of)
                    .orElseGet(() -> Typed.refused("no online player named '" + raw + "'"));
            case BOOLEAN -> bool(raw);
            case CHOICE -> choices.contains(raw) ? Typed.of(raw)
                    : Typed.refused("'" + raw + "' is not one of " + String.join(", ", choices));
        };
    }

    private static Typed wholeNumber(String raw) {
        try {
            return Typed.of(Integer.toString(Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return Typed.refused("'" + raw + "' is not a whole number");
        }
    }

    private static Typed number(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (Double.isFinite(value)) {
                return Typed.of(Double.toString(value));
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        return Typed.refused("'" + raw + "' is not a number");
    }

    private static Typed bool(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true" -> Typed.of("true");
            case "false" -> Typed.of("false");
            default -> Typed.refused("'" + raw + "' is not true or false");
        };
    }

    /**
     * One parse's outcome: the canonical value, or the refusal.
     *
     * @param value the canonical text, when valid
     * @param error why it is not valid, or null
     */
    public record Typed(String value, String error) {

        public static Typed of(String value) {
            return new Typed(value, null);
        }

        public static Typed refused(String error) {
            return new Typed(null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }
}
