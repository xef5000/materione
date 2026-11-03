package dev.materione.plugin.gui.list;

import java.util.Map;

/**
 * The page state of one list in one session (logic.md §6.7): the page shown, from 1, and how many pages the list has
 * at least 1. Stored in the session's pages map under the list's id, so a rule reads it as {@code gui.page.<list>.number}
 * and {@code gui.page.<list>.count} (for a "Page 2/5" text).
 *
 * @param number the page shown, from 1 up to {@code count}
 * @param count the number of pages, at least 1
 */
public record ListPage(int number, int count) {

    /** The state of a list that has not been rendered yet: the first of one page. */
    public static final ListPage FIRST = new ListPage(1, 1);

    public ListPage {
        if (number < 1 || count < 1 || number > count) {
            throw new IllegalArgumentException("a page is 1 to the page count, not " + number + " of " + count);
        }
    }

    /** The value the session stores: an immutable map with {@code number} and {@code count}. */
    public Map<String, Object> toMap() {
        return Map.of("number", number, "count", count);
    }

    /** The page a session stores under a list, or {@link #FIRST} when it stores none (or a malformed value). */
    public static ListPage of(Object stored) {
        if (stored instanceof Map<?, ?> map && map.get("number") instanceof Number number
                && map.get("count") instanceof Number count) {
            int total = Math.max(1, count.intValue());
            return new ListPage(Math.min(Math.max(1, number.intValue()), total), total);
        }
        return FIRST;
    }
}
