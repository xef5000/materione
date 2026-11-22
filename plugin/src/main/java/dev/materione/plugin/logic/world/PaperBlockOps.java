package dev.materione.plugin.logic.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * {@link BlockOps} over Paper (logic.md §41). The break-as-player path is {@code Player#breakBlock}, which fires the
 * {@code BlockBreakEvent} a mined block fires, uses the player's tool and game mode, and drops what the tool would. A
 * break that must not drop is the same event with {@code setDropItems(false)}, then the block removed. A block that
 * Materione owns (a custom block) cancels the break event to run its own teardown, so a cancelled event with an empty
 * block afterwards is a success, not a veto.
 *
 * <p>The caller opens the {@link VerbScope} around these calls, so the bridges answer the events they raise with every rule but
 * the one that caused them.
 */
public final class PaperBlockOps implements BlockOps {

    private final CustomBlocksHolder custom;

    /** The custom-block port is read at call time: it is bound after the logic registry is built. */
    public interface CustomBlocksHolder {
        /** The custom-block runtime, or null before it exists. */
        CustomBlocks current();
    }

    public PaperBlockOps(CustomBlocksHolder custom) {
        this.custom = custom;
    }

    @Override
    public boolean isAir(Block block) {
        return block.getType().isAir();
    }

    @Override
    public boolean breakable(Block block) {
        Material type = block.getType();
        return !type.isAir() && !block.isLiquid() && type.getHardness() >= 0;
    }

    @Override
    public Result breakAs(Player player, Block block, boolean drops) {
        if (drops) {
            boolean broken = player.breakBlock(block);
            return broken || block.getType().isAir() ? Result.DONE : Result.DENIED;
        }
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setDropItems(false);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return block.getType().isAir() ? Result.DONE : Result.DENIED;
        }
        block.setType(Material.AIR, true);
        return Result.DONE;
    }

    @Override
    public Result breakPlain(Block block, boolean drops) {
        CustomBlocks blocks = custom.current();
        if (blocks != null && blocks.remove(block, drops)) {
            return Result.DONE;
        }
        if (drops) {
            return block.breakNaturally() ? Result.DONE : Result.FAILED;
        }
        block.setType(Material.AIR, true);
        return Result.DONE;
    }

    @Override
    public Result breakWith(ItemStack tool, Block block, boolean drops) {
        CustomBlocks blocks = custom.current();
        if (blocks != null && blocks.remove(block, drops)) {
            return Result.DONE;
        }
        if (drops) {
            boolean broken = tool == null ? block.breakNaturally() : block.breakNaturally(tool);
            return broken ? Result.DONE : Result.FAILED;
        }
        block.setType(Material.AIR, true);
        return Result.DONE;
    }

    @Override
    public boolean mayPlace(Player player, Block block, BlockData data) {
        BlockState replaced = block.getState();
        Material material = data.getMaterial();
        ItemStack hand = new ItemStack(material.isItem() ? material : Material.STONE);
        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, block, hand, player, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled() && event.canBuild();
    }

    @Override
    public Result place(Block block, BlockData data) {
        block.setBlockData(data, true);
        return Result.DONE;
    }
}
