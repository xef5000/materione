package dev.materione.plugin.runtime;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.platform.items.MaterioneStacks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/**
 * The item auto-updater (runtime-systems §3): stacks with a Materione tag
 * are compared against the current definition; changed stacks are rebuilt
 * <em>preserving volatile state</em> — the refresh re-applies only our
 * components, so durability, in-game enchantments and custom counters
 * survive. Runs on join (the throttled scan pass lands with the M2 event
 * surface; the join path already covers the "pack migrated while away"
 * case). All inventory mutation goes through the scheduling gateway
 * (R1): the player's thread owns its inventory.
 */
public final class ItemAutoUpdater implements Listener {

    private final Plugin plugin;
    private final ContentRuntime runtime;
    private final DiagnosticsBus diagnostics;
    private final SchedulingGateway scheduling;

    public ItemAutoUpdater(
            Plugin plugin, ContentRuntime runtime, DiagnosticsBus diagnostics, SchedulingGateway scheduling) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // double gateway hop: async detect, entity-thread mutate
        scheduling.executeEntity(plugin, player, () -> updateInventory(player));
    }

    /** Rebuilds every Materione stack in the inventory that drifted from its definition. */
    void updateInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            NamespacedId identity = MaterioneStacks.identityOf(stack).orElse(null);
            if (identity == null) {
                continue;
            }
            RuntimeItem item = runtime.registries().item(identity).orElse(null);
            if (item == null) {
                continue; // unknown id: vanilla render, flagged for admin via doctor
            }
            MaterioneStacks.refresh(stack, toSpec(item));
            inventory.setItem(slot, stack);
        }
    }

    private static MaterioneStacks.StackSpec toSpec(RuntimeItem item) {
        return new MaterioneStacks.StackSpec(
                item.id(), item.material(), item.modelId(), item.displayName(), item.lore());
    }
}
