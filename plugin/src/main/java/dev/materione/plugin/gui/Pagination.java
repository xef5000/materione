package dev.materione.plugin.gui;

/**
 * Page math for paginated menus — pure and headless-testable (no Bukkit
 * imports). A window is described by its start index; {@code page} is
 * 0-based everywhere and clamped into range by the callers' entry point.
 */
public record Pagination(int total, int perPage) {

    public Pagination {
        if (perPage <= 0) {
            throw new IllegalArgumentException("perPage must be positive: " + perPage);
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must be non-negative: " + total);
        }
    }

    /** Pages are zero-based; an empty menu still has one (empty) page. */
    public int pageCount() {
        return Math.max(1, (total + perPage - 1) / perPage);
    }

    /** The page actually shown when a caller asks for {@code page}. */
    public int clamp(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    /** First index of {@code page}; only meaningful for pages in range. */
    public int start(int page) {
        return clamp(page) * perPage;
    }

    /** End-exclusive last index of {@code page}, bounded by {@code total}. */
    public int end(int page) {
        return Math.min(total, start(page) + perPage);
    }

    public boolean hasNext(int page) {
        return clamp(page) < pageCount() - 1;
    }

    public boolean hasPrevious(int page) {
        return clamp(page) > 0;
    }
}
