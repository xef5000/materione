package dev.materione.plugin.gui.list;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.logic.OverlayLogicContext;
import dev.materione.content.gui.list.ListSearch;
import dev.materione.content.gui.list.ListSort;
import dev.materione.plugin.gui.render.RenderReporter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Arranges a list's entries for one render (logic.md §6.7): the search and the filter keep the entries that show, then
 * the sort orders them. Paging comes after, in {@link ListPager}, so the page count is the count of the arranged
 * entries.
 *
 * <p>This is the one place a list's visible entries are decided (logic.md §28.21). The author's {@code filter:} and a
 * session search are two conditions over the same entries, and an entry shows only when both hold: the search keeps
 * the entries whose listed fields contain the session text (ignoring case), and an empty or unset text keeps all.
 *
 * <p>Pure apart from the reporter. A filter that throws leaves its entry out and is reported ({@code MTR-0078}); the
 * other entries still show. Sorting cannot fail: an entry with no value for the field goes last, and values that are
 * not both numbers compare as text.
 */
public final class ListEntries {

    private ListEntries() {}

    /**
     * The entries a list shows, in order.
     *
     * @param context the window's context; each entry is read through an overlay of it as {@code entry.*}, and a
     *     search reads its text from {@code gui.session.<var>}
     * @param source the entries as the source produced them, unfiltered and unsorted
     * @param reporter where a filter that threw is reported
     * @param gui the GUI the list belongs to, for the report
     */
    public static List<Map<String, Object>> arrange(
            CompiledListLayer layer, LogicContext context, List<Map<String, Object>> source,
            RenderReporter reporter, NamespacedId gui) {
        var kept = new ArrayList<Map<String, Object>>(source.size());
        String needle = layer.search() == null ? null : searchText(layer.search(), context);
        for (Map<String, Object> entry : source) {
            if (needle != null && !matchesSearch(layer.search(), entry, needle)) {
                continue;
            }
            if (layer.filter() == null) {
                kept.add(entry);
                continue;
            }
            try {
                if (layer.filter().test(new OverlayLogicContext(context, "entry", entry))) {
                    kept.add(entry);
                }
            } catch (RuntimeException failed) {
                reporter.problem(DiagnosticCodes.GUI_LIST_ENTRY_FAILED, gui, layer.layerId(),
                        "filter could not be evaluated, so an entry is left out: "
                                + (failed.getMessage() != null ? failed.getMessage() : failed.toString()));
            }
        }
        ListSort sort = layer.sort();
        if (sort != null && kept.size() > 1) {
            kept.sort(comparator(sort));
        }
        return kept;
    }

    /**
     * The search text the window's session holds for {@code search}, trimmed and lower-cased, or null when there is
     * none (unset, blank, or not text or a number): a null search keeps every entry.
     */
    static String searchText(ListSearch search, LogicContext context) {
        Object raw = context.resolve(List.of("gui", "session", search.var()));
        if (!(raw instanceof String) && !(raw instanceof Number)) {
            return null;
        }
        String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return text.isEmpty() ? null : text;
    }

    /** Whether an entry's listed field contains the (lower-cased) search text, ignoring case. */
    static boolean matchesSearch(ListSearch search, Map<String, Object> entry, String needle) {
        for (String field : search.fields()) {
            Object value = entry.get(field);
            if (value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The order of a sort: nulls last whatever the direction, the field's values compared otherwise. */
    static Comparator<Map<String, Object>> comparator(ListSort sort) {
        String field = sort.field();
        boolean descending = sort.descending();
        return (left, right) -> {
            Object a = left.get(field);
            Object b = right.get(field);
            if (a == null || b == null) {
                return a == null ? (b == null ? 0 : 1) : -1;
            }
            int order = compareValues(a, b);
            return descending ? -order : order;
        };
    }

    /** Two field values in their natural order: numbers numerically, booleans false first, anything else as text. */
    static int compareValues(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        String text = String.valueOf(a);
        String other = String.valueOf(b);
        int folded = text.compareToIgnoreCase(other);
        return folded != 0 ? folded : text.compareTo(other);
    }
}
