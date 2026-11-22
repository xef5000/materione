package dev.materione.plugin.commands;

import java.util.List;
import java.util.Objects;

/**
 * One compiled {@code args} entry of a command or an {@code open:} block (logic.md §6.1, §40): its name, its type, whether
 * it may be left out, its default in canonical text, the values tab completion offers, the bounds of a number, and the
 * options of a player.
 *
 * @param name the argument name, {@code [a-z][a-z0-9_]*}; {@code args.<name>} and {@code gui.args.<name>} read it
 * @param type the typed parse
 * @param optional whether it may be left out
 * @param defaultValue the canonical default, or null when there is none
 * @param suggest the completions and, for a {@code choice}, the allowed values
 * @param min the smallest value an {@code int} or {@code number} accepts, or null; whole for an {@code int}
 * @param max the largest value an {@code int} or {@code number} accepts, or null; whole for an {@code int}
 * @param offline whether a {@code player} may also be anyone who has joined before, not only someone online
 * @param selector whether a {@code player} may also be a vanilla selector (the tree then carries the selector type)
 */
public record OpenArgSpec(
        String name,
        ArgType type,
        boolean optional,
        String defaultValue,
        List<String> suggest,
        Double min,
        Double max,
        boolean offline,
        boolean selector) {

    public OpenArgSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        suggest = suggest == null ? List.of() : List.copyOf(suggest);
    }

    /** An argument with no bounds and no player options. */
    public OpenArgSpec(
            String name, ArgType type, boolean optional, String defaultValue, List<String> suggest) {
        this(name, type, optional, defaultValue, suggest, null, null, false, false);
    }

    /** Whether the command or API must pass it: not optional, and no default to fall back on. */
    public boolean required() {
        return !optional && defaultValue == null;
    }

    /** Whether the argument is a player the tree types as a vanilla selector (it may then name several). */
    public boolean selecting() {
        return type == ArgType.PLAYER && selector;
    }
}
