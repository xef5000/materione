package dev.materione.plugin.logic.variable;

import dev.materione.api.logic.variable.StackRef;
import dev.materione.api.logic.variable.StackVariableStore;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ObservedStackVariableStore implements StackVariableStore {

    private static final Logger LOG = Logger.getLogger(ObservedStackVariableStore.class.getName());
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private final StackVariableStore delegate;
    private final Supplier<VariableWriteObserver> observer;

    ObservedStackVariableStore(StackVariableStore delegate, Supplier<VariableWriteObserver> observer) {
        this.delegate = delegate;
        this.observer = observer;
    }

    @Override
    public VariableScope scope() {
        return delegate.scope();
    }

    @Override
    public Optional<Object> read(VariableDeclaration declaration, StackRef stack) {
        return delegate.read(declaration, stack);
    }

    @Override
    public boolean write(VariableDeclaration declaration, StackRef stack, Object value) {
        boolean written = delegate.write(declaration, stack, value);
        if (written) {
            try {
                observer.get().stackWritten(declaration, stack);
            } catch (RuntimeException failure) {
                if (REPORTED.compareAndSet(false, true)) {
                    LOG.log(Level.WARNING, "An item variable write observer threw; the write stands (reported once)",
                            failure);
                }
            }
        }
        return written;
    }
}
