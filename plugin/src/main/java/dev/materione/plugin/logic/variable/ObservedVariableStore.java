package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wraps one scope's store so a {@link VariableWriteObserver} hears every write that succeeded, on both paths of the
 * port: {@link #write} and {@link #mutate}. Reads pass through untouched. A write that changes nothing still
 * notifies: the store does not compare.
 */
final class ObservedVariableStore implements VariableStore {

    private final VariableStore delegate;
    private final Supplier<VariableWriteObserver> observer;

    ObservedVariableStore(VariableStore delegate, Supplier<VariableWriteObserver> observer) {
        this.delegate = delegate;
        this.observer = observer;
    }

    @Override
    public VariableScope scope() {
        return delegate.scope();
    }

    @Override
    public Optional<Object> read(VariableDeclaration declaration, UUID holder) {
        return delegate.read(declaration, holder);
    }

    @Override
    public boolean write(VariableDeclaration declaration, UUID holder, Object value) {
        boolean written = delegate.write(declaration, holder, value);
        if (written) {
            observer.get().written(declaration, holder);
        }
        return written;
    }

    @Override
    public VariableOps.Outcome mutate(
            VariableDeclaration declaration, UUID holder, VariableOp operation, Object operand) {
        VariableOps.Outcome outcome = delegate.mutate(declaration, holder, operation, operand);
        if (outcome.ok()) {
            observer.get().written(declaration, holder);
        }
        return outcome;
    }
}
