package dev.materione.api.logic;

import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Objects;

/**
 * A list source's self-description (logic.md §4.5, §6.7).
 *
 * @param self the name, summary and parameter schema, the same shape every registry entry describes with
 * @param shorthand the parameter a scalar payload is read under ({@code pack} for {@code pack_items: shop}),
 *     or {@code null} when the source takes only a mapping
 * @param entry the entry schema: the {@code entry.*} variables its entries carry, for the editor's
 *     autocomplete; a family ({@code entry.*}) when the fields are the author's own
 * @param cached true when reading the entries is expensive: the window reads them when it opens, on
 *     {@code view.refresh} and on a reload, and keeps them between refresh-clock ticks and page changes
 * @param announced true when the source reads nothing but the variables its parameters name (a parameter drawn with
 *     the {@code variable} widget): a write to one of them is then every change the list can see, so a view needs no
 *     polling for it. False, the default, says the source may read live state no write announces, and a view that
 *     uses it is polled
 */
public record ListSourceDescription(SelfDescription self, String shorthand, List<ContextVariable> entry,
        boolean cached, boolean announced) {

    public ListSourceDescription {
        Objects.requireNonNull(self, "self");
        if (shorthand != null && shorthand.isBlank()) {
            throw new IllegalArgumentException("a shorthand is a non-blank parameter name or null");
        }
        entry = entry == null ? List.of() : List.copyOf(entry);
    }

    /** A source that does not say what it reads: its views are polled. */
    public ListSourceDescription(SelfDescription self, String shorthand, List<ContextVariable> entry, boolean cached) {
        this(self, shorthand, entry, cached, false);
    }

    /** This description for a source that reads only the variables its parameters name. */
    public ListSourceDescription announcing() {
        return new ListSourceDescription(self, shorthand, entry, cached, true);
    }

    /** A source whose entries are read on every render, with no shorthand. */
    public static ListSourceDescription of(SelfDescription self, List<ContextVariable> entry) {
        return new ListSourceDescription(self, null, entry, false);
    }
}
