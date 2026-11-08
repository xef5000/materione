package dev.materione.core.pipeline.validate;

import dev.materione.api.NamespacedId;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.TemplatePools;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a pure validator may look at: parsed packs, the asset index, the painting-texture sizes
 * recorded at parse time and the template pool the parse will resolve against (templates, and items
 * under their own keys: {@link TemplatePools}). No filesystem, no clock — validators stay pure
 * (plugin architecture §4.3, ArchUnit rule R6).
 */
public record ValidationEnvironment(
        List<ContentPack> packs,
        Set<NamespacedId> knownAssets,
        Map<NamespacedId, int[]> paintingSizes,
        Map<String, Map<String, Object>> templatePool) {

    public ValidationEnvironment {
        packs = List.copyOf(packs);
        knownAssets = Set.copyOf(knownAssets);
        paintingSizes = Map.copyOf(paintingSizes);
        templatePool = Map.copyOf(templatePool);
    }

    /** The pool is derived from the packs: validation resolves chains exactly as the compile will. */
    public ValidationEnvironment(
            List<ContentPack> packs, Set<NamespacedId> knownAssets, Map<NamespacedId, int[]> paintingSizes) {
        this(packs, knownAssets, paintingSizes, TemplatePools.of(packs));
    }

    /** Two-component arity kept for tests: no painting sizes recorded. */
    public ValidationEnvironment(List<ContentPack> packs, Set<NamespacedId> knownAssets) {
        this(packs, knownAssets, Map.of());
    }
}
