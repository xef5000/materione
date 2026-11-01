package dev.materione.plugin.logic.variable;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * One run's {@link VariableBinding}: the live declarations, and the stores of the run's player and of
 * the server. Built per fired event by {@link VariableService#bind}, so a run reads the declarations
 * of the shadow it started with and the value of the player it belongs to.
 *
 * <p>A run with no player (an explosion) reaches the server scope and no player scope: its player
 * reads are absent and its player writes are refused.
 */
final class RunVariables implements VariableBinding {

    private final Function<NamespacedId, Optional<VariableDeclaration>> declared;
    private final Map<VariableScope, VariableStore> stores;
    private final UUID player;

    RunVariables(
            Function<NamespacedId, Optional<VariableDeclaration>> declared,
            Map<VariableScope, VariableStore> stores,
            UUID player) {
        this.declared = declared;
        this.stores = stores;
        this.player = player;
    }

    @Override
    public Optional<VariableDeclaration> declaration(NamespacedId id) {
        return declared.apply(id);
    }

    @Override
    public Optional<Object> stored(VariableDeclaration declaration) {
        VariableStore store = stores.get(declaration.scope());
        return store == null ? Optional.empty() : store.read(declaration, holderOf(declaration));
    }

    @Override
    public boolean store(VariableDeclaration declaration, Object value) {
        VariableStore store = stores.get(declaration.scope());
        return store != null && store.write(declaration, holderOf(declaration), value);
    }

    @Override
    public VariableOps.Outcome mutate(VariableDeclaration declaration, VariableOp operation, Object operand) {
        VariableStore store = stores.get(declaration.scope());
        return store == null ? new VariableOps.Outcome(false, null, "variable store is unavailable")
                : store.mutate(declaration, holderOf(declaration), operation, operand);
    }

    /** The player a declaration is held by: the run's player for player scope, nobody for server scope. */
    private UUID holderOf(VariableDeclaration declaration) {
        return declaration.scope() == VariableScope.PLAYER ? player : null;
    }
}
