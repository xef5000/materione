package dev.materione.plugin.runtime.behavior;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.platform.items.MaterioneStacks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The M2 item-event bridge (runtime-systems §2.1 world/item subset):
 * click (left/right), break, equip, unequip, drop, pickup, consume, damage,
 * death — each resolves the involved stack's Materione identity and fires
 * the matching rules. Listeners are static wiring; rules come from the
 * live registry snapshot at event entry, so reloads need no listener churn
 * (the deliberate difference from incumbents' register/unregister dance).
 *
 * <p>Runs at HIGH: protection plugins decide first, behavior runs after.
 */
public final class ItemBehaviorListener implements Listener {

    private final BehaviorService behavior;
    private final DiagnosticsBus diagnostics;

    public ItemBehaviorListener(BehaviorService behavior, DiagnosticsBus diagnostics) {
        this.behavior = behavior;
        this.diagnostics = diagnostics;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        String detail = switch (event.getAction()) {
            case LEFT_CLICK_BLOCK, LEFT_CLICK_AIR -> "left";
            case RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR -> "right";
            default -> null; // PHYSICAL belongs to the block runtime (M2 slice 3)
        };
        if (detail == null || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return; // off-hand would double-fire every right click
        }
        try {
            behavior.fireItemEvent("click", detail, event.getPlayer(), event.getItem(), event);
        } catch (RuntimeException e) {
            reportBridgeFailure(event.getPlayer(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        try {
            behavior.fireItemEvent("break", "", event.getPlayer(), tool, event);
        } catch (RuntimeException e) {
            reportBridgeFailure(event.getPlayer(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        ItemStack next = player.getInventory().getItem(event.getNewSlot());
        try {
            if (hasIdentity(previous)) {
                behavior.fireItemEvent("unequip", "", player, previous, event);
            }
            if (hasIdentity(next)) {
                behavior.fireItemEvent("equip", "", player, next, event);
            }
        } catch (RuntimeException e) {
            reportBridgeFailure(player, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        try {
            behavior.fireItemEvent(
                    "drop", "", event.getPlayer(), event.getItemDrop().getItemStack(), event);
        } catch (RuntimeException e) {
            reportBridgeFailure(event.getPlayer(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        try {
            behavior.fireItemEvent("pickup", "", player, event.getItem().getItemStack(), event);
        } catch (RuntimeException e) {
            reportBridgeFailure(player, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        try {
            behavior.fireItemEvent("consume", "", event.getPlayer(), event.getItem(), event);
        } catch (RuntimeException e) {
            reportBridgeFailure(event.getPlayer(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(PlayerItemDamageEvent event) {
        try {
            behavior.fireItemEvent("damage", "", event.getPlayer(), event.getItem(), event);
        } catch (RuntimeException e) {
            reportBridgeFailure(event.getPlayer(), e);
        }
    }

    /** Death fires once per dropped stack so per-item death rules resolve naturally. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        for (ItemStack drop : event.getDrops()) {
            try {
                behavior.fireItemEvent("death", "", event.getEntity(), drop, null);
            } catch (RuntimeException e) {
                reportBridgeFailure(event.getEntity(), e);
            }
        }
    }

    private static boolean hasIdentity(ItemStack stack) {
        return MaterioneStacks.identityOf(stack).isPresent();
    }

    private void reportBridgeFailure(Player player, RuntimeException e) {
        diagnostics.publish(new dev.materione.api.diagnostics.Diagnostic(
                DiagnosticCodes.ACTION_FAILED,
                dev.materione.api.diagnostics.Severity.WARN,
                "behavior bridge failed for " + player.getName() + ": " + e.getMessage(),
                null, null, "the event continues vanilla handling"));
    }
}
