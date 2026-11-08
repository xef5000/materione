package dev.materione.plugin.gui.list;

import java.util.Map;

/**
 * The page state of one list in one session (logic.md §6.7): the page shown, from 1, how many pages the list has (at
 * least 1) and how many entries it has in all. Stored in the session's pages map under the list's id, so a rule reads
 * it as {@code gui.page.<list>.number}, {@code gui.page.<list>.count} (for a "Page 2/5" text) and
 * {@code gui.page.<list>.size} (for "no results": a size of 0).
 *
 * @param number the page shown, from 1 up to {@code count}
 * @param count the number of pages, at least 1
 * @param size the entries the list has after its search and filter, over every page; 0 when it has none
 */
public record ListPage(int number, int count, int size) {

    /** The state of a list that has not been rendered yet: the first of one page, with nothing known to be in it. */
    public static final ListPage FIRST = new ListPage(1, 1, 0);

    public ListPage {
        if (number < 1 || count < 1 || number > count || size < 0) {
            throw new IllegalArgumentException(
                    "a page is 1 to the page count, not " + number + " of " + count + " (" + size + " entries)");
        }
    }

    /** The value the session stores: an immutable map with {@code number}, {@code count} and {@code size}. */
    public Map<String, Object> toMap() {
        return Map.of("number", number, "count", count, "size", size);
    }

    /** The page a session stores under a list, or {@link #FIRST} when it stores none (or a malformed value). */
    public static ListPage of(Object stored) {
        if (stored instanceof Map<?, ?> map && map.get("number") instanceof Number number
                && map.get("count") instanceof Number count) {
            int total = Math.max(1, count.intValue());
            int size = map.get("size") instanceof Number entries ? Math.max(0, entries.intValue()) : 0;
            return new ListPage(Math.min(Math.max(1, number.intValue()), total), total, size);
        }
        return FIRST;
    }
}
