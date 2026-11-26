package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.api.logic.variable.StackVariableStore;
import dev.materione.api.logic.variable.VariableDeclaration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The item scope of the variable store (logic.md §38): each value lives in the stack's own persistent data under
 * {@code materione:var.<pack>.<key>}, the key the player scope uses in a player's container, next to where the
 * {@code durability} mechanic keeps its counters. The value therefore travels with the stack: it survives a drop, a
 * pick-up, a chest, an anvil and a restart with no store of its own, and Minecraft will not merge two stacks whose
 * data differ.
 *
 * <p>The state of a stack is its set of keys, so the store keeps that set minimal: a value equal to the declared
 * default clears the key instead of storing it (two stacks in the same effective state then carry the same bytes and
 * stack together), and a stack whose variables are all at their defaults is byte-identical to one that never had any.
 *
 * <p>A stack is touched on the thread of the player the run belongs to, decided by {@code access} exactly as for the
 * player scope: an offline owner reads absent and refuses writes, an owner on another region throws
 * {@link ReadUnavailableException} for a read and refuses a write. A handle whose stack has since become a
 * different item (its physical or Materione identity changed) or no longer exists reads absent and refuses writes.
 */
public final class PdcStackVariableStore implements StackVariableStore {

    private final Function<UUID, PdcVariableStore.Access> access;

    /** @param access whether the calling thread may touch the owner's stacks; the player store's probe */
    public PdcStackVariableStore(Function<UUID, PdcVariableStore.Access> access) {
        this.access = access;
    }

    @Override
    public Optional<Object> read(VariableDeclaration declaration, StackRef stack) {
        if (!(stack instanceof BukkitStackRef ref)) {
            return Optional.empty();
        }
        if (ref.owner() != null) {
            PdcVariableStore.Access state = access.apply(ref.owner());
            if (state == PdcVariableStore.Access.OFFLINE) {
                return Optional.empty();
            }
            if (state != PdcVariableStore.Access.OWNED) {
                throw new ReadUnavailableException("item variable read requires the owner's region");
            }
        }
        StackPdc current = ref.pdc();
        if (!ref.matches(current)) {
            return Optional.empty();
        }
        Optional<PersistentDataContainer> data = current.view();
        if (data.isEmpty()) {
            return Optional.empty();
        }
        NamespacedKey key = PdcVariableStore.keyOf(declaration);
        try {
            return switch (declaration.type()) {
                case NUMBER -> Optional.<Object>ofNullable(data.get().get(key, PersistentDataType.DOUBLE));
                case STRING -> Optional.<Object>ofNullable(data.get().get(key, PersistentDataType.STRING));
                case BOOLEAN -> Optional.<Object>ofNullable(data.get().get(key, PersistentDataType.BOOLEAN));
            };
        } catch (RuntimeException otherType) {
            return Optional.empty(); // a value stored with a type the declaration no longer has is absent
        }
    }

    @Override
    public boolean write(VariableDeclaration declaration, StackRef stack, Object value) {
        if (!(stack instanceof BukkitStackRef ref)) {
            return false;
        }
        if (ref.owner() != null && access.apply(ref.owner()) != PdcVariableStore.Access.OWNED) {
            return false;
        }
        StackPdc current = ref.pdc();
        if (!ref.matches(current)) {
            return false;
        }
        NamespacedKey key = PdcVariableStore.keyOf(declaration);
        // the default is the absence of the key, so equal states are equal bytes
        Object stored = value != null && value.equals(declaration.defaultValue()) ? null : value;
        try {
            return current.edit(data -> {
                if (stored == null) {
                    data.remove(key);
                    return;
                }
                switch (declaration.type()) {
                    case NUMBER -> data.set(key, PersistentDataType.DOUBLE, ((Number) stored).doubleValue());
                    case STRING -> data.set(key, PersistentDataType.STRING, (String) stored);
                    case BOOLEAN -> data.set(key, PersistentDataType.BOOLEAN, (Boolean) stored);
                }
            });
        } catch (RuntimeException rejected) {
            return false; // a value of the wrong Java type: nothing is written
        }
    }

}
