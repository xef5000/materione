package dev.materione.behavior.logic;

import dev.materione.api.NamespacedId;
import java.util.Optional;

/**
 * How authored ids map to registry ids and back (logic.md §4): Materione's own types may be written
 * without their {@code materione:} namespace, an addon's types must be qualified, so an addon can
 * never shadow a built-in by accident (the rule {@code mechanics.md} sets for mechanics).
 */
public final class LogicIds {

    /** The namespace of every type Materione registers itself. */
    public static final String BUILT_IN_NAMESPACE = "materione";

    private LogicIds() {}

    /**
     * The registry id an author's spelling names: {@code take_money} is {@code materione:take_money},
     * {@code example:greet} is itself. Empty for a spelling that is not a valid id.
     */
    public static Optional<NamespacedId> parse(String authored) {
        if (authored == null) {
            return Optional.empty();
        }
        String text = authored.trim();
        try {
            return Optional.of(text.indexOf(':') < 0
                    ? NamespacedId.of(BUILT_IN_NAMESPACE, text)
                    : NamespacedId.parse(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The spelling an author uses and the vocabulary reports: bare for Materione's own, else qualified. */
    public static String display(NamespacedId id) {
        return BUILT_IN_NAMESPACE.equals(id.namespace()) ? id.value() : id.toString();
    }
}
