package dev.materione.plugin.gui.open;

import java.util.List;
import java.util.Objects;

/**
 * One compiled {@code open.args} entry (logic.md §6.1): its name, its type, whether it may be left out,
 * its default in canonical text, and the values tab completion offers.
 *
 * @param name the argument name, {@code [a-z][a-z0-9_]*}; {@code gui.args.<name>} reads it
 * @param type the typed parse
 * @param optional whether it may be left out
 * @param defaultValue the canonical default, or null when there is none
 * @param suggest the completions and, for a {@code choice}, the allowed values
 */
public record OpenArgSpec(String name, ArgType type, boolean optional, String defaultValue, List<String> suggest) {

    public OpenArgSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        suggest = suggest == null ? List.of() : List.copyOf(suggest);
    }

    /** Whether the command or API must pass it: not optional, and no default to fall back on. */
    public boolean required() {
        return !optional && defaultValue == null;
    }
}
