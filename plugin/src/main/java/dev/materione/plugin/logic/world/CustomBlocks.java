package dev.materione.plugin.logic.world;

import dev.materione.api.NamespacedId;
import org.bukkit.block.Block;

/**
 * What the world verbs need from the custom-block runtime (logic.md §41): to place a Materione block by id and to take
 * one down through the runtime's own removal path (placement index, drops), so a verb never leaves a record of a block
 * that is no longer there. The runtime implements it; it exists once the first content has been applied.
 */
public interface CustomBlocks {

    /** Whether a live Materione block has this id. */
    boolean exists(NamespacedId id);

    /** Places the block {@code id} at {@code block}; false when the id is unknown or the block cannot be encoded. */
    boolean place(Block block, NamespacedId id);

    /**
     * Removes the block at {@code block} when it is a Materione block, through the runtime's removal path.
     *
     * @return false when it is not one (the caller breaks it as a vanilla block)
     */
    boolean remove(Block block, boolean drop);
}
