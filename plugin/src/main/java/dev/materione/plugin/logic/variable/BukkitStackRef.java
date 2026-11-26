package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.StackRef;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The platform's {@link StackRef}: one physical stack, the player whose thread owns it, and the Materione identity
 * the stack had when the run was given it (logic.md §38).
 *
 * <p>The subject is the physical stack itself, never its bytes. Paper hands out distinct Bukkit mirrors around one
 * server stack and edits its data in place, so every mirror of that stack names the same subject, through any amount
 * change or variable write. Another stack is another subject even when its bytes are equal: two identical swords are
 * two subjects, and an identical stack put where the subject was does not inherit its handles.
 */
public final class BukkitStackRef implements StackRef {

    private final UUID owner;
    private final Supplier<StackPdc> locate;
    private final String identity;
    private final Object physical;

    /** A handle fixed to this data (tests and stacks that are not in a player's inventory). */
    BukkitStackRef(UUID owner, StackPdc pdc) {
        this(owner, () -> pdc, pdc.identity());
    }

    /**
     * A handle to the subject {@code locate} currently yields: the stack it yields now is the subject, and a later
     * call that yields another physical stack yields a stale handle.
     */
    BukkitStackRef(UUID owner, Supplier<StackPdc> locate, String identity) {
        this.owner = owner;
        this.locate = locate;
        this.identity = identity;
        StackPdc initial = locate.get();
        this.physical = initial == null || initial == StackPdc.EMPTY ? null : initial.physicalIdentity();
    }

    /**
     * A handle on {@code stack} as given, or null when it is no stack (air or an empty hand). Use
     * {@link #of(ItemStack, Player)} for a stack the player carries.
     */
    public static BukkitStackRef of(ItemStack stack, UUID owner) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return null;
        }
        StackPdc pdc = StackPdc.of(stack);
        return new BukkitStackRef(owner, () -> pdc, pdc.identity());
    }

    /**
     * A handle on a stack the player carries. The handle finds that physical stack in the player's inventory at each
     * access, so it follows the stack when it moves between slots and goes stale when the stack leaves the inventory.
     * A stack the player does not carry gets the fixed handle of {@link #of(ItemStack, UUID)}.
     */
    public static BukkitStackRef of(ItemStack stack, Player owner) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0 || owner == null) {
            return null;
        }
        Object physical = StackPdc.of(stack).physicalIdentity();
        int slot = slotOf(owner.getInventory(), physical, 0);
        if (slot < 0) {
            return of(stack, owner.getUniqueId());
        }
        UUID id = owner.getUniqueId();
        int[] hint = {slot};
        return new BukkitStackRef(id, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                return StackPdc.EMPTY;
            }
            PlayerInventory inventory = online.getInventory();
            int found = slotOf(inventory, physical, hint[0]);
            if (found < 0) {
                return StackPdc.EMPTY;
            }
            hint[0] = found;
            return StackPdc.of(inventory.getItem(found));
        }, StackPdc.of(stack).identity());
    }

    /** The slot holding {@code physical}, trying {@code hint} first; -1 when the inventory does not hold it. */
    private static int slotOf(PlayerInventory inventory, Object physical, int hint) {
        int size = inventory.getSize();
        if (hint >= 0 && hint < size && StackPdc.of(inventory.getItem(hint)).physicalIdentity() == physical) {
            return hint;
        }
        for (int slot = 0; slot < size; slot++) {
            if (StackPdc.of(inventory.getItem(slot)).physicalIdentity() == physical) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public UUID owner() {
        return owner;
    }

    /** The subject's current data, or {@link StackPdc#EMPTY} when the locator yields nothing. */
    StackPdc pdc() {
        StackPdc current = locate.get();
        return current == null ? StackPdc.EMPTY : current;
    }

    /** Whether {@code current} is this handle's subject: the same physical stack, still holding the same item. */
    boolean matches(StackPdc current) {
        return physical != null
                && current.physicalIdentity() == physical
                && identity.equals(current.identity());
    }

    /** The identity the stack had when this handle was made; blank for a vanilla item. */
    public String identity() {
        return identity;
    }

    @Override
    public boolean isLive() {
        StackPdc current = pdc();
        return matches(current) && current.view().isPresent();
    }

    /**
     * Two handles are equal when they name the same physical stack of the same owner. Equality does not depend on
     * liveness, so a handle keeps its place in a hash map after its stack is gone.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof BukkitStackRef that
                && physical != null
                && physical == that.physical
                && Objects.equals(owner, that.owner)
                && identity.equals(that.identity);
    }

    @Override
    public int hashCode() {
        return physical == null ? System.identityHashCode(this) : System.identityHashCode(physical);
    }

    @Override
    public Map<String, Object> itemView() {
        StackPdc current = pdc();
        return matches(current) ? current.itemView() : Map.of();
    }

    /**
     * Edits the stack's item meta in place (the stack-editing verb, logic.md §41), through the same checks every access
     * of this handle makes: the stack must still exist and still hold the identity the handle was given.
     *
     * @return false when the stack is gone or is now another item, or could not be edited
     */
    public boolean editMeta(java.util.function.Consumer<org.bukkit.inventory.meta.ItemMeta> edit) {
        StackPdc current = pdc();
        return matches(current) && current.editMeta(edit);
    }
}
