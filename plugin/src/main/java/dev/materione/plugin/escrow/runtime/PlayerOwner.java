package dev.materione.plugin.escrow.runtime;

import dev.materione.api.scheduling.SchedulingGateway;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * {@link Owner} over a Bukkit player: every task runs through the scheduling gateway's entity scheduler (the player's
 * own thread; on Folia the thread of the region that owns them, §5.6), and {@link #save()} is the platform's own save.
 *
 * <p><b>{@link #save()} is the one call of {@code Player.saveData()} in the plugin</b> (gui-input-durability.md §5.4,
 * the owner-approved D8 exception): {@code EscrowBarrierBansTest} fails the build if any other method of any class
 * calls it, and if any class but {@link SaveBarrier} calls {@link Owner#save()}.
 */
public final class PlayerOwner implements Owner<ItemStack> {

    private static final long TICK_MILLIS = 50L;

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final Player player;
    private final CustodyAccount escrow;
    private final String accountKey;
    private final Supplier<Inventory> window;

    /**
     * @param window the player's open window's own inventory, asked when a step needs the live state; null result when
     *     none is open
     */
    public PlayerOwner(Plugin plugin, SchedulingGateway scheduling, Player player, CustodyAccount escrow,
            Supplier<Inventory> window) {
        this(plugin, scheduling, player, escrow, "materione:" + EscrowRuntime.ACCOUNT_KEY, window);
    }

    public PlayerOwner(Plugin plugin, SchedulingGateway scheduling, Player player, CustodyAccount escrow,
            String accountKey, Supplier<Inventory> window) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.player = player;
        this.escrow = escrow;
        this.accountKey = accountKey;
        this.window = window;
    }

    @Override
    public UUID id() {
        return player.getUniqueId();
    }

    @Override
    public CustodyAccount escrow() {
        return escrow;
    }

    @Override
    public String accountKey() {
        return accountKey;
    }

    @Override
    public Live<ItemStack> live() {
        return new BukkitLive(player, window.get());
    }

    @Override
    public void run(Runnable task, Runnable retired) {
        scheduling.executeEntityLater(plugin, player, 0, task, retired);
    }

    @Override
    public void runAfter(long millis, Runnable task, Runnable retired) {
        scheduling.executeEntityLater(plugin, player, Math.max(1, (millis + TICK_MILLIS - 1) / TICK_MILLIS), task, retired);
    }

    @Override
    public void save() {
        player.saveData();
    }
}
