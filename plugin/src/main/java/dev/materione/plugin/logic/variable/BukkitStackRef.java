package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.StackRef;
import java.util.Map;
import java.util.function.Supplier;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The platform's {@link StackRef}: a live Bukkit stack, the player whose thread owns it, and the Materione identity
 * the stack had when the run was given it (logic.md §38). The store checks that identity on every access, so a run
 * that resumes after a wait never reads or writes into a different item that now sits in the same object.
 */
public final class BukkitStackRef implements StackRef {

    private final UUID owner;
    private final Supplier<StackPdc> pdc;
    private final String identity;

    BukkitStackRef(UUID owner, StackPdc pdc) {
        this(owner, () -> pdc, pdc.identity());
    }

    private BukkitStackRef(UUID owner, Supplier<StackPdc> pdc, String identity) {
        this.owner = owner;
        this.pdc = pdc;
        this.identity = identity;
    }

    /**
     * The handle of {@code stack} for a run that belongs to {@code owner}, or null when there is no stack to hold
     * state (null or air): a run with no stack reads an item variable as its default.
     */
    public static BukkitStackRef of(ItemStack stack, UUID owner) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return null;
        }
        return new BukkitStackRef(owner, StackPdc.of(stack));
    }

    /**
     * A handle to the matching live inventory slot when the event's stack is one the player carries. The slot is
     * fixed for the handle's lifetime, so opening a view does not leave it pointing at the pre-open item mirror.
     */
    public static BukkitStackRef of(ItemStack stack, Player owner) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0 || owner == null) {
            return null;
        }
        var inventory = owner.getInventory();
        int held = inventory.getHeldItemSlot();
        ItemStack live = inventory.getItem(held);
        if (same(live, stack)) {
            return at(owner, held);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            live = inventory.getItem(slot);
            if (live == stack) {
                return at(owner, slot);
            }
        }
        return of(stack, owner.getUniqueId());
    }

    private static BukkitStackRef at(Player owner, int slot) {
        ItemStack initial = owner.getInventory().getItem(slot);
        return new BukkitStackRef(owner.getUniqueId(),
                () -> StackPdc.of(owner.getInventory().getItem(slot)),
                StackPdc.of(initial).identity());
    }

    private static boolean same(ItemStack live, ItemStack event) {
        return live != null && !live.getType().isAir()
                && live.getType() == event.getType() && live.isSimilar(event);
    }

    @Override
    public UUID owner() {
        return owner;
    }

    StackPdc pdc() {
        StackPdc current = pdc.get();
        return current == null ? StackPdc.EMPTY : current;
    }

    /** The identity the stack had when this handle was made; blank for a vanilla item. */
    public String identity() {
        return identity;
    }

    @Override
    public Map<String, Object> itemView() {
        StackPdc current = pdc();
        return identity.equals(current.identity()) ? current.itemView() : Map.of();
    }

    /**
     * Edits the stack's item meta in place (the stack-editing verb, logic.md §41), through the same checks every access
     * of this handle makes: the stack must still exist and still hold the identity the handle was given.
     *
     * @return false when the stack is gone or is now another item, or could not be edited
     */
    public boolean editMeta(java.util.function.Consumer<org.bukkit.inventory.meta.ItemMeta> edit) {
        StackPdc current = pdc();
        return identity.equals(current.identity()) && current.editMeta(edit);
    }
}
