package dev.materione.content.gui.list;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How a list orders its entries, as authored (logic.md §6.7): sorting happens after the filter and before paging,
 * so the page count is the count of the sorted, filtered entries.
 *
 * <p>Written as a field name ({@code sort: name}, ascending) or as {@code {by: name, order: desc}}.
 *
 * @param field the entry field to compare ({@code name}, {@code level})
 * @param descending true for a descending order
 */
public record ListSort(String field, boolean descending) {

    private static final Pattern FIELD = Pattern.compile("[a-z_][a-z0-9_]*");

    public ListSort {
        Objects.requireNonNull(field, "field");
    }

    /**
     * Parses one authored {@code sort:} value.
     *
     * @throws IllegalArgumentException with the reason, when the value is not a field and an order
     */
    public static ListSort parse(Object raw) {
        if (raw instanceof String text) {
            return new ListSort(field(text), false);
        }
        if (raw instanceof Map<?, ?> mapping) {
            if (!(mapping.get("by") instanceof String by)) {
                throw new IllegalArgumentException("sort needs by: the entry field to sort on");
            }
            boolean descending = false;
            if (mapping.get("order") != null) {
                if (!(mapping.get("order") instanceof String order)
                        || !("asc".equals(order) || "desc".equals(order))) {
                    throw new IllegalArgumentException("sort order is asc or desc");
                }
                descending = "desc".equals(order);
            }
            for (Object key : mapping.keySet()) {
                if (!"by".equals(key) && !"order".equals(key)) {
                    throw new IllegalArgumentException("sort takes by and order, not '" + key + "'");
                }
            }
            return new ListSort(field(by), descending);
        }
        throw new IllegalArgumentException("sort is an entry field, or {by: <field>, order: asc|desc}");
    }

    private static String field(String text) {
        String trimmed = text.trim();
        if (!FIELD.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("sort by '" + text + "' is not an entry field name");
        }
        return trimmed;
    }
}
