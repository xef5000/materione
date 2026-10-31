package dev.materione.plugin.gui.dynamic;

import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.ItemSource;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;

/**
 * An item source with its parameters already parsed (logic.md §4.4): what a compiled GUI item layer holds
 * instead of the raw authored value. The source's parameter type is captured at {@link #bind}, so the
 * layer can hold any source without a type parameter of its own.
 */
@FunctionalInterface
public interface ItemBinding {

    /** Resolves the item for one viewer. Called on the viewer's thread. */
    ItemResolution resolve(LogicContext context);

    /**
     * Parses {@code params} with {@code source} and binds the result.
     *
     * @throws IllegalArgumentException when the source rejects its parameters
     */
    static <P> ItemBinding bind(ItemSource<P> source, ParamReader params) {
        P parsed = source.parse(params);
        return context -> source.resolve(parsed, context);
    }

    /** The binding of a source that is not installed: it resolves to the reason, shown as a barrier. */
    static ItemBinding unavailable(String reason) {
        return context -> ItemResolution.unresolved(reason);
    }
}
