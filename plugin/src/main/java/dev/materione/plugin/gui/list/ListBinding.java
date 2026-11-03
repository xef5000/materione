package dev.materione.plugin.gui.list;

import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import java.util.List;
import java.util.Map;

/**
 * A list source with its parameters already parsed (logic.md §4.5): what a compiled list layer holds instead of
 * the raw authored value. The source's parameter type is captured at {@link #bind}, so a layer can hold any source
 * without a type parameter of its own. Reading may throw; the caller contains it (see {@link ListRead#failed}).
 */
@FunctionalInterface
public interface ListBinding {

    /** Reads the source's entries for one viewer. Called on the viewer's thread. */
    ListRead read(LogicContext context);

    /**
     * Parses {@code params} with {@code source} and binds the result.
     *
     * @throws IllegalArgumentException when the source rejects its parameters
     */
    static <P> ListBinding bind(ListSource<P> source, ParamReader params) {
        P parsed = source.parse(params);
        return context -> {
            try {
                return ListRead.ok(source.entries(parsed, context));
            } catch (RuntimeException failed) {
                return ListRead.failed(failed.getMessage() != null ? failed.getMessage() : failed.toString());
            }
        };
    }

    /**
     * What a read produced: the entries, or the reason it failed (the list then shows nothing and the reason is
     * reported as {@code MTR-0083}).
     *
     * @param entries the entries in the source's order; empty on failure
     * @param failure why the read failed, or null when it did not
     */
    record ListRead(List<Map<String, Object>> entries, String failure) {

        public ListRead {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public static ListRead ok(List<Map<String, Object>> entries) {
            return new ListRead(entries, null);
        }

        public static ListRead failed(String reason) {
            return new ListRead(List.of(), reason == null || reason.isBlank() ? "unknown reason" : reason);
        }

        public boolean failed() {
            return failure != null;
        }
    }
}
