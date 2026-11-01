package dev.materione.behavior.run;

import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.ReadRetiredException;
import dev.materione.api.logic.Threading;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/** Owner-dispatches reads for one run and retires all pending reads when that run ends. */
public final class RunReadExecutor implements ReadExecutor, AutoCloseable {
    private final RunScheduler scheduler;
    private final Object anchor;
    private final BooleanSupplier synchronousOnly;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Pending<?>> pending = ConcurrentHashMap.newKeySet();

    public RunReadExecutor(RunScheduler scheduler, Object anchor) {
        this(scheduler, anchor, () -> false);
    }

    public RunReadExecutor(RunScheduler scheduler, Object anchor, BooleanSupplier synchronousOnly) {
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        this.anchor = anchor;
        this.synchronousOnly = java.util.Objects.requireNonNull(synchronousOnly, "synchronousOnly");
    }

    /** Whether the owning run still accepts reads. */
    @Override
    public boolean active() {
        return !closed.get();
    }

    @Override
    public ReadExecutor captureOrigin() {
        Threading origin = scheduler.currentOwner(anchor);
        return new ReadExecutor() {
            @Override public boolean active() { return RunReadExecutor.this.active(); }
            @Override public <T> CompletionStage<T> on(Threading owner, Supplier<T> operation) {
                return RunReadExecutor.this.on(owner, operation);
            }
            @Override public <T> CompletionStage<T> onOrigin(Supplier<T> operation) {
                return origin == null
                        ? CompletableFuture.failedFuture(new ReadUnavailableException(
                                "the expression's original owner is unavailable"))
                        : RunReadExecutor.this.on(origin, operation);
            }
        };
    }

    @Override
    public <T> CompletionStage<T> on(Threading owner, Supplier<T> operation) {
        var read = new Pending<T>();
        if (closed.get()) {
            read.cancel();
            return read.result;
        }
        pending.add(read);
        read.result.whenComplete((value, error) -> {
            read.preventStart();
            pending.remove(read);
        });
        if (closed.get()) {
            read.cancel();
            return read.result;
        }
        try {
            if (scheduler.canRunInline(anchor, owner)) {
                read.run(operation);
                return read.result;
            }
            if (synchronousOnly.getAsBoolean()) {
                read.result.completeExceptionally(new ReadUnavailableException(
                        "read requires " + owner + " before a synchronous event returns"));
                return read.result;
            }
            RunScheduler.Handle handle = scheduler.schedule(anchor, owner, 0,
                    () -> read.run(operation), read::retire);
            read.setHandle(handle);
        } catch (RuntimeException failure) {
            read.result.completeExceptionally(failure);
        }
        return read.result;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            pending.forEach(Pending::cancel);
        }
    }

    private static final class Pending<T> {
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private static final int WAITING = 0;
        private static final int STARTED = 1;
        private static final int CANCELLED = 2;
        // Starting and cancellation race at this atomic transition; neither waits for user code.
        private final AtomicInteger state = new AtomicInteger(WAITING);
        private final AtomicReference<RunScheduler.Handle> handle = new AtomicReference<>();

        void setHandle(RunScheduler.Handle next) {
            handle.set(next);
            if (result.isDone()) {
                next.cancel();
            }
        }

        void run(Supplier<T> operation) {
            if (!state.compareAndSet(WAITING, STARTED) || result.isDone()) {
                return;
            }
            try {
                result.complete(operation.get());
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }

        void retire() {
            state.getAndSet(CANCELLED);
            result.completeExceptionally(new ReadRetiredException("player retired before the read"));
        }

        void preventStart() {
            state.compareAndSet(WAITING, CANCELLED);
        }

        void cancel() {
            state.getAndSet(CANCELLED);
            result.completeExceptionally(new CancellationException("run ended before the read"));
            RunScheduler.Handle current = handle.get();
            if (current != null) {
                current.cancel();
            }
        }
    }
}
