package dev.materione.api.logic;

import dev.materione.api.registry.ParameterType;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One entry of a trigger's context schema (logic.md §2.6): a variable path the trigger guarantees
 * to its rules ({@code player.name}, {@code click.slot}, {@code view.args.*}). The compiler checks
 * {@code ${...}} paths against the schema and the studio autocompletes from it, so one declaration
 * serves checking and editing (D5).
 *
 * @param path dotted lowercase path; a trailing {@code .*} stands for a family of names
 *        ({@code view.args.*} for each declared argument)
 * @param type the value kind an expression sees
 * @param description one line for autocomplete
 * @param hosts the content types whose runtime fills this variable ({@code item}, {@code block},
 *        {@code furniture}, later {@code gui}); empty when every host that fires the trigger does
 * @param filter a trigger filter whose bound values further qualify this variable, or null when
 *        every firing provides it
 * @param filterValues the values of {@code filter} which provide this variable
 */
public record ContextVariable(
        String path,
        ParameterType type,
        String description,
        List<String> hosts,
        String filter,
        List<String> filterValues) {

    private static final Pattern FORMAT = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*(\\.\\*)?");

    public ContextVariable {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        if (!FORMAT.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "Invalid context path '" + path + "': dotted lowercase segments, optional trailing '.*'");
        }
        description = description == null ? "" : description;
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        filter = filter == null || filter.isBlank() ? null : filter;
        filterValues = filterValues == null ? List.of() : List.copyOf(filterValues);
        if ((filter == null) != filterValues.isEmpty()) {
            throw new IllegalArgumentException("a conditional context variable needs a filter and at least one value");
        }
    }

    public ContextVariable(String path, ParameterType type, String description, List<String> hosts) {
        this(path, type, description, hosts, null, List.of());
    }

    /** A variable every host of the trigger provides. */
    public static ContextVariable of(String path, ParameterType type, String description) {
        return new ContextVariable(path, type, description, List.of());
    }

    /** The same variable, provided only by the named hosts. */
    public ContextVariable providedBy(String... hostNames) {
        return new ContextVariable(path, type, description, List.of(hostNames), filter, filterValues);
    }

    /** The same variable, provided only when a trigger filter includes one of the named values. */
    public ContextVariable when(String filter, String... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("a conditional context variable needs at least one value");
        }
        return new ContextVariable(path, type, description, hosts, filter, List.of(values));
    }
}
