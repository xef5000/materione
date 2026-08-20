package dev.materione.core.pipeline.validate;

import dev.materione.api.NamespacedId;
import dev.materione.core.pipeline.ContentPack;
import java.util.List;
import java.util.Set;

/**
 * Everything a pure validator may look at: parsed packs and the asset index
 * recorded at parse time. No filesystem, no clock — validators stay pure
 * (plugin architecture §4.3, ArchUnit rule R6).
 */
public record ValidationEnvironment(List<ContentPack> packs, Set<NamespacedId> knownAssets) {

    public ValidationEnvironment {
        packs = List.copyOf(packs);
        knownAssets = Set.copyOf(knownAssets);
    }
}
