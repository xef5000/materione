package dev.materione.api.logic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Nonblocking owner dispatch for an opt-in resumable read. An executor runs an operation inline
 * when already owned, otherwise schedules it, completes its stage on the execution thread, and
 * completes exceptionally if the run is cancelled or the entity retires. It never waits for a
 * scheduler, and must not run an operation after cancellation.
 */
public interface ReadExecutor {
    /** False after cancellation or retirement. */
    default boolean active() {
        return true;
    }

    <T> CompletionStage<T> on(Threading owner, Supplier<T> operation);

    /**
     * Captures the current evaluation owner before operands can suspend. An executor that cannot
     * establish an owner leaves legacy callbacks unavailable rather than guessing.
     */
    default ReadExecutor captureOrigin() {
        return this;
    }

    /** Runs a legacy callback on the captured owner. */
    default <T> CompletionStage<T> onOrigin(Supplier<T> operation) {
        return CompletableFuture.failedFuture(new ReadUnavailableException(
                "the expression's original owner is unavailable"));
    }

    /** Inline execution for callers that already own every requested thread, such as Paper. */
    ReadExecutor INLINE = new ReadExecutor() {
        @Override
        public <T> CompletionStage<T> on(Threading owner, Supplier<T> operation) {
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public <T> CompletionStage<T> onOrigin(Supplier<T> operation) {
            return on(Threading.ANY, operation);
        }
    };
}
