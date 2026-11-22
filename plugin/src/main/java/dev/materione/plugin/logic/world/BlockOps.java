package dev.materione.plugin.logic.world;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The platform's way to change one block (logic.md §41), in two voices. <b>As a player</b> the change goes through the
 * path a player's own action takes, so a protection plugin is asked exactly as it would be for a pickaxe or a placed
 * block: a {@code BlockBreakEvent} (through {@code Player#breakBlock}) or a {@code BlockPlaceEvent}, and a cancelled
 * event is a {@link Result#DENIED}. <b>Plain</b> is the world acting with no actor: no event is asked, which is why a
 * verb takes it only when the author writes {@code as_player: false}.
 *
 * <p>Both run on the thread that owns the block; the caller arranges that ({@link Regions}).
 */
public interface BlockOps {

    /** What became of one block. */
    enum Result {
        /** The block was changed. */
        DONE,
        /** A listener vetoed the change (protection, a claim, a rule). */
        DENIED,
        /** The change could not be made at all (the block cannot be changed, the data was refused). */
        FAILED
    }

    /** Whether {@code block} is empty air. */
    boolean isAir(Block block);

    /** Whether a verb may try to break {@code block}: not air, not a liquid, not unbreakable (bedrock, barriers). */
    boolean breakable(Block block);

    /** Breaks {@code block} as {@code player}: the player's own break path, with or without the block's drops. */
    Result breakAs(Player player, Block block, boolean drops);

    /** Breaks {@code block} with no actor and no event. */
    Result breakPlain(Block block, boolean drops);

    /**
     * Breaks {@code block} the way {@code tool} would, with no event: the break of {@code protection_mode: port}, where the
     * port has already answered and the event path is not run (protection.md section 4). The tool's drops apply; its
     * durability is not spent. The default is a plain break.
     */
    default Result breakWith(ItemStack tool, Block block, boolean drops) {
        return breakPlain(block, drops);
    }

    /** Asks whether {@code player} may place {@code data} at {@code block} (a {@code BlockPlaceEvent}); changes nothing. */
    boolean mayPlace(Player player, Block block, BlockData data);

    /** Replaces {@code block} with {@code data}, with no event. */
    Result place(Block block, BlockData data);
}
