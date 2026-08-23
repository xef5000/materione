package dev.materione.plugin.runtime.behavior;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.rule.Rule;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The compiled behavior rules of block definitions in the applied shadow,
 * keyed by owning block. Same atomic-swap contract as {@link ItemBehaviors}.
 */
public final class BlockBehaviors {

    /** The registry-view key. */
    public static final Class<BlockBehaviors> KEY = BlockBehaviors.class;

    private final Map<NamespacedId, List<Rule>> byBlock;

    public BlockBehaviors(Map<NamespacedId, List<Rule>> byBlock) {
        this.byBlock = Map.copyOf(byBlock);
    }

    public static BlockBehaviors empty() {
        return new BlockBehaviors(Map.of());
    }

    public Optional<List<Rule>> forBlock(NamespacedId id) {
        return Optional.ofNullable(byBlock.get(id));
    }
}
