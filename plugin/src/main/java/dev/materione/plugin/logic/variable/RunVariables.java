package dev.materione.plugin.logic.variable;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.api.logic.variable.StackVariableStore;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One run's {@link VariableBinding}: the live declarations, and the stores of the run's player, of the server, of the
 * run's view instance and of the run's item stack. Built per fired event by {@link VariableService#bind}, so a run reads the declarations
 * of the shadow it started with and the values of the player and the stack it belongs to.
 *
 * <p>A run with no player (an explosion) reaches the server scope and no player scope: its player reads are absent
 * and its player writes are refused. A run with no stack (a trigger that is not about an item, or an empty hand)
 * reaches no item scope the same way, and reports it ({@code onStackMiss}, rate-limited by the service) because
 * the author asked a stack for something it cannot answer (logic.md §38).
 */
final class RunVariables implements VariableBinding {

    private final Function<NamespacedId, Optional<VariableDeclaration>> declared;
    private final Map<VariableScope, VariableStore> stores;
    private final UUID player;
    private final StackVariableStore stackStore;
    private final StackRef stack;
    private final BiConsumer<VariableDeclaration, String> onStackMiss;
    private final UUID view;

    RunVariables(
            Function<NamespacedId, Optional<VariableDeclaration>> declared,
            Map<VariableScope, VariableStore> stores,
            UUID player) {
        this(declared, stores, player, null, null, (declaration, what) -> { });
    }

    RunVariables(
            Function<NamespacedId, Optional<VariableDeclaration>> declared,
            Map<VariableScope, VariableStore> stores,
            UUID player,
            StackVariableStore stackStore,
            StackRef stack,
            BiConsumer<VariableDeclaration, String> onStackMiss) {
        this(declared, stores, player, stackStore, stack, onStackMiss, null);
    }

    RunVariables(
            Function<NamespacedId, Optional<VariableDeclaration>> declared,
            Map<VariableScope, VariableStore> stores,
            UUID player,
            StackVariableStore stackStore,
            StackRef stack,
            BiConsumer<VariableDeclaration, String> onStackMiss,
            UUID view) {
        this.declared = declared;
        this.stores = stores;
        this.player = player;
        this.stackStore = stackStore;
        this.stack = stack;
        this.onStackMiss = onStackMiss;
        this.view = view;
    }

    @Override
    public Optional<StackRef> stack() {
        return Optional.ofNullable(stack);
    }

    @Override
    public Optional<VariableDeclaration> declaration(NamespacedId id) {
        return declared.apply(id);
    }

    @Override
    public Optional<Object> stored(VariableDeclaration declaration) {
        if (declaration.scope() == VariableScope.ITEM) {
            if (stack == null || stackStore == null) {
                onStackMiss.accept(declaration, "read");
                return Optional.empty();
            }
            return stackStore.read(declaration, stack);
        }
        VariableStore store = stores.get(declaration.scope());
        return store == null ? Optional.empty() : store.read(declaration, holderOf(declaration));
    }

    @Override
    public boolean store(VariableDeclaration declaration, Object value) {
        if (declaration.scope() == VariableScope.ITEM) {
            if (stack == null || stackStore == null) {
                onStackMiss.accept(declaration, "write");
                return false;
            }
            return stackStore.write(declaration, stack, value);
        }
        VariableStore store = stores.get(declaration.scope());
        return store != null && store.write(declaration, holderOf(declaration), value);
    }

    @Override
    public VariableOps.Outcome mutate(VariableDeclaration declaration, VariableOp operation, Object operand) {
        if (declaration.scope() == VariableScope.ITEM) {
            return new VariableOps.Outcome(false, null, "atomic variable mutation is unsupported for item variables");
        }
        VariableStore store = stores.get(declaration.scope());
        return store == null ? new VariableOps.Outcome(false, null, "variable store is unavailable")
                : store.mutate(declaration, holderOf(declaration), operation, operand);
    }

    /**
     * The holder a declaration is held by: the run's player for player scope, the run's view instance for session
     * scope, nobody for server scope. No view instance means no holder, and the session store reads empty and refuses
     * writes.
     */
    private UUID holderOf(VariableDeclaration declaration) {
        return switch (declaration.scope()) {
            case PLAYER -> player;
            case SESSION -> view;
            default -> null;
        };
    }
}
