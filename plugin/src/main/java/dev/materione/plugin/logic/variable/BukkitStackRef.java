package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.StackRef;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * The platform's {@link StackRef}: a live Bukkit stack, the player whose thread owns it, and the Materione identity
 * the stack had when the run was given it (logic.md §38). The store checks that identity on every access, so a run
 * that resumes after a wait never reads or writes into a different item that now sits in the same object.
 */
public final class BukkitStackRef implements StackRef {

    private final UUID owner;
    private final StackPdc pdc;
    private final String identity;

    BukkitStackRef(UUID owner, StackPdc pdc) {
        this.owner = owner;
        this.pdc = pdc;
        this.identity = pdc.identity();
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

    @Override
    public UUID owner() {
        return owner;
    }

    StackPdc pdc() {
        return pdc;
    }

    /** The identity the stack had when this handle was made; blank for a vanilla item. */
    public String identity() {
        return identity;
    }

    /**
     * Edits the stack's item meta in place (the stack-editing verb, logic.md §41), through the same checks every access
     * of this handle makes: the stack must still exist and still hold the identity the handle was given.
     *
     * @return false when the stack is gone or is now another item, or could not be edited
     */
    public boolean editMeta(java.util.function.Consumer<org.bukkit.inventory.meta.ItemMeta> edit) {
        return identity.equals(pdc.identity()) && pdc.editMeta(edit);
    }
}
