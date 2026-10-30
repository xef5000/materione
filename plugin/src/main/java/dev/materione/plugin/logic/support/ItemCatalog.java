package dev.materione.plugin.logic.support;

import dev.materione.core.runtime.RuntimeRegistries;
import java.util.Optional;

/**
 * The live item registries the item actions resolve Materione ids against. The plugin supplies
 * the current published set (it swaps atomically on apply, plugin.md §4.5); empty before the first
 * successful content load, when a Materione item cannot be given or matched.
 */
@FunctionalInterface
public interface ItemCatalog {

    /** The registries of the content currently live, or empty before the first apply. */
    Optional<RuntimeRegistries> current();

    /** A catalog with nothing loaded; for tests and for hosts without content. */
    static ItemCatalog none() {
        return Optional::empty;
    }
}
