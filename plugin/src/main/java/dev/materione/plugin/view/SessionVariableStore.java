package dev.materione.plugin.view;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariableType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link VariableScope#SESSION} store (views.md §3.4): the values of every open view instance, behind the same
 * {@link VariableStore} port as the player and server scopes, so a write to a session variable is one more write on the
 * port and a decorator that watches the port sees it. The holder is the view instance ({@link ViewerRecord#instance()}),
 * not a player.
 *
 * <p>Like the other stores it holds values, never defaults: an unset value reads as empty and the caller takes the
 * declaration's default. A write to an instance that is not open (closed, replaced, or never admitted) returns
 * false, the way a write to an offline player does. The values of an instance end with it: {@link ViewerRecord#end}
 * drops them, and {@link ViewerRecord#snapshot()} / {@link ViewerRecord#restore} carry them across {@code view.back}
 * without going through the port (a restored instance has no render yet to notify).
 *
 * <p>A write reports itself to the instance's record as a mutation ({@link ViewerRecord#mutating}), so the owner-thread
 * check of views.md §5.6 covers session values like the rest of viewer state.
 */
public final class SessionVariableStore implements VariableStore {

    private record Instance(ViewerRecord record, Map<NamespacedId, Object> values) {}

    private final ConcurrentHashMap<UUID, Instance> instances = new ConcurrentHashMap<>();

    @Override
    public VariableScope scope() {
        return VariableScope.SESSION;
    }

    @Override
    public Optional<Object> read(VariableDeclaration declaration, UUID instance) {
        Instance found = find(instance);
        return found == null ? Optional.empty() : Optional.ofNullable(found.values().get(declaration.id()));
    }

    @Override
    @ViewerMutator
    public boolean write(VariableDeclaration declaration, UUID instance, Object value) {
        Instance found = find(instance);
        if (found == null || value != null && !declaration.type().accepts(value)) {
            return false;
        }
        found.record().mutating("session value");
        if (value == null) {
            found.values().remove(declaration.id());
        } else {
            found.values().put(declaration.id(), declaration.type().coerce(value).orElseThrow());
        }
        return true;
    }

    @Override
    @ViewerMutator
    public VariableOps.Outcome mutate(
            VariableDeclaration declaration, UUID instance, VariableOp operation, Object operand) {
        Instance found = find(instance);
        if (found == null) {
            return new VariableOps.Outcome(false, null, "no open view holds this session variable");
        }
        Object safeOperand = null;
        if (operation == VariableOp.SET || operation == VariableOp.ADD
                || operation == VariableOp.SUBTRACT || operation == VariableOp.MULTIPLY) {
            var checked = operation == VariableOp.SET
                    ? VariableOps.apply(VariableOp.SET, declaration.type(), null, operand)
                    : VariableOps.apply(VariableOp.MULTIPLY, VariableType.NUMBER, 1d, operand);
            if (!checked.ok()) {
                return checked;
            }
            safeOperand = checked.value();
        }
        found.record().mutating("session value");
        VariableOps.Outcome[] outcome = new VariableOps.Outcome[1];
        Object operandNow = safeOperand;
        found.values().compute(declaration.id(), (id, stored) -> {
            Object current = stored != null ? stored : declaration.defaultValue();
            outcome[0] = VariableOps.apply(operation, declaration.type(), current, operandNow);
            return outcome[0].ok() ? outcome[0].value() : stored;
        });
        return outcome[0];
    }

    /** Opens {@code record}'s instance, empty. Idempotent. */
    void open(ViewerRecord record) {
        instances.computeIfAbsent(record.instance(), ignored -> new Instance(record, new ConcurrentHashMap<>()));
    }

    /** Drops an instance and its values. */
    void close(UUID instance) {
        instances.remove(instance);
    }

    /** A copy of one instance's values (empty for an instance that is not open). */
    Map<NamespacedId, Object> values(UUID instance) {
        Instance found = find(instance);
        return found == null ? Map.of() : Map.copyOf(found.values());
    }

    /** Puts {@code values} into an open instance. */
    void restore(UUID instance, Map<NamespacedId, Object> values) {
        Instance found = find(instance);
        if (found != null) {
            found.values().putAll(values);
        }
    }

    /** How many instances are open. */
    public int openInstances() {
        return instances.size();
    }

    private Instance find(UUID instance) {
        return instance == null ? null : instances.get(instance);
    }
}
