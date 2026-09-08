package dev.materione.core.pipeline.validate;

import dev.materione.api.NamespacedId;
import dev.materione.core.pipeline.ContentPack;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a pure validator may look at: parsed packs, the asset index and
 * the painting-texture sizes recorded at parse time. No filesystem, no clock
 * — validators stay pure (plugin architecture §4.3, ArchUnit rule R6).
 */
public record ValidationEnvironment(
        List<ContentPack> packs, Set<NamespacedId> knownAssets, Map<NamespacedId, int[]> paintingSizes) {

    public ValidationEnvironment {
        packs = List.copyOf(packs);
        knownAssets = Set.copyOf(knownAssets);
        paintingSizes = Map.copyOf(paintingSizes);
    }

    /** Two-component arity kept for tests: no painting sizes recorded. */
    public ValidationEnvironment(List<ContentPack> packs, Set<NamespacedId> knownAssets) {
        this(packs, knownAssets, Map.of());
    }
}
