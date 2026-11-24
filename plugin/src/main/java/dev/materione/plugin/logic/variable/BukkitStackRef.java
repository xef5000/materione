package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.StackRef;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The platform's {@link StackRef}: a live Bukkit stack, the player whose thread owns it, and the physical and
 * logical identity the stack had when the run was given it (logic.md §38).
 */
public final class BukkitStackRef implements StackRef {

    private static final Map<Object, Subject> PHYSICAL_SUBJECTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Subject> LOGICAL_SUBJECTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final UUID owner;
    private final Supplier<StackPdc> pdc;
    private final String identity;
    private final boolean followsSlot;
    private final Subject subject;
    private Object physical;
    private Object logical;

    BukkitStackRef(UUID owner, StackPdc pdc) {
        this(owner, () -> pdc, pdc.identity(), false);
    }

    BukkitStackRef(UUID owner, Supplier<StackPdc> pdc, String identity) {
        this(owner, pdc, identity, true);
    }

    private BukkitStackRef(UUID owner, Supplier<StackPdc> pdc, String identity, boolean followsSlot) {
        this.owner = owner;
        this.pdc = pdc;
        this.identity = identity;
        this.followsSlot = followsSlot;
        StackPdc initial = pdc.get();
        this.physical = initial == null ? null : initial.physicalIdentity();
        this.logical = initial == null ? null : initial.logicalIdentity();
        this.subject = subjectOf(owner, identity, physical, logical, followsSlot);
    }

    /**
     * The handle of {@code stack} for a run that belongs to {@code owner}, or null when there is no stack to hold
     * state (null or air): a run with no stack reads an item variable as its default.
     */
    public static BukkitStackRef of(ItemStack stack, UUID owner) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return null;
        }
        return new BukkitStackRef(owner, () -> StackPdc.of(stack), StackPdc.of(stack).identity(), false);
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
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack live = inventory.getItem(slot);
            if (StackPdc.of(live).physicalIdentity() == StackPdc.of(stack).physicalIdentity()) {
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

    @Override
    public UUID owner() {
        return owner;
    }

    StackPdc pdc() {
        StackPdc current = pdc.get();
        return current == null ? StackPdc.EMPTY : current;
    }

    boolean matches(StackPdc current) {
        if (!identity.equals(current.identity())) {
            return false;
        }
        Object nowPhysical = current.physicalIdentity();
        Object nowLogical = current.logicalIdentity();
        synchronized (PHYSICAL_SUBJECTS) {
            Subject currentSubject = PHYSICAL_SUBJECTS.get(nowPhysical);
            if (currentSubject == subject) {
                rememberLogical(nowLogical);
                physical = nowPhysical;
                return true;
            }
            if (followsSlot) {
                synchronized (LOGICAL_SUBJECTS) {
                    currentSubject = LOGICAL_SUBJECTS.get(nowLogical);
                    if (currentSubject == subject) {
                        LOGICAL_SUBJECTS.remove(logical);
                        PHYSICAL_SUBJECTS.put(nowPhysical, subject);
                        physical = nowPhysical;
                        logical = nowLogical;
                        return true;
                    }
                }
            }
        }
        return false;
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
     * The same physical stack reached through two inventory/event mirrors is the same subject. Amount and item
     * variable writes are deliberately absent from the key: they change state, not subject identity.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof BukkitStackRef that
                && Objects.equals(owner, that.owner)
                && identity.equals(that.identity)
                && subject == that.subject
                && isLive()
                && that.isLive();
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(subject);
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

    private void rememberLogical(Object next) {
        if (!followsSlot || Objects.equals(logical, next)) {
            logical = next;
            return;
        }
        synchronized (LOGICAL_SUBJECTS) {
            LOGICAL_SUBJECTS.remove(logical);
            logical = next;
        }
    }

    private static Subject subjectOf(
            UUID owner, String identity, Object physical, Object logical, boolean followsSlot) {
        if (physical == null) {
            return new Subject(owner, identity);
        }
        synchronized (PHYSICAL_SUBJECTS) {
            Subject subject = matching(PHYSICAL_SUBJECTS.get(physical), owner, identity);
            boolean matchedLogical = false;
            if (subject == null && followsSlot) {
                synchronized (LOGICAL_SUBJECTS) {
                    subject = matching(LOGICAL_SUBJECTS.get(logical), owner, identity);
                    if (subject != null) {
                        LOGICAL_SUBJECTS.remove(logical);
                        matchedLogical = true;
                    }
                }
            }
            if (subject == null) {
                subject = new Subject(owner, identity);
            }
            PHYSICAL_SUBJECTS.put(physical, subject);
            if (followsSlot && logical != null && !matchedLogical) {
                LOGICAL_SUBJECTS.put(logical, subject);
            }
            return subject;
        }
    }

    private static Subject matching(Subject subject, UUID owner, String identity) {
        return subject != null && Objects.equals(subject.owner, owner) && subject.identity.equals(identity)
                ? subject : null;
    }

    private static final class Subject {
        final UUID owner;
        final String identity;

        Subject(UUID owner, String identity) {
            this.owner = owner;
            this.identity = identity;
        }
    }
}
