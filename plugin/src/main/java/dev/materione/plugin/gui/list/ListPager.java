package dev.materione.plugin.gui.list;

import java.util.List;

/**
 * The paging arithmetic of a list (logic.md §6.7), pure: how many pages a list of entries has, which page a stored
 * number means once the entries have changed, and which entries a page shows. A list with no entries still has one
 * (empty) page, so "Page 1/1" is always a sensible text.
 */
public final class ListPager {

    private ListPager() {}

    /** The pages of {@code entries} entries at {@code size} per page: at least 1. */
    public static int countOf(int entries, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("a page holds at least one entry");
        }
        return Math.max(1, (entries + size - 1) / size);
    }

    /** The page a stored number means among {@code count} pages: clamped into 1 to {@code count}. */
    public static int clamp(int number, int count) {
        return Math.min(Math.max(number, 1), count);
    }

    /** The entries of page {@code number} (from 1) at {@code size} per page; empty past the last entry. */
    public static <T> List<T> pageOf(List<T> entries, int number, int size) {
        int from = Math.min((number - 1) * size, entries.size());
        int to = Math.min(from + size, entries.size());
        return List.copyOf(entries.subList(from, to));
    }
}
