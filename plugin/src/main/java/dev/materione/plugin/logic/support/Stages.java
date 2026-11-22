package dev.materione.plugin.logic.support;

import dev.materione.api.logic.ActionOutcome;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Small helpers for the actions that chain their reads and effects as stages (logic.md §41). */
public final class Stages {

    private Stages() {}

    /** A stage that has already failed the action with {@code reason}. */
    public static CompletionStage<ActionOutcome> failed(String reason) {
        return CompletableFuture.completedFuture(ActionOutcome.failed(reason));
    }

    /**
     * The outcome of a stage that was built over an inline executor, for an action's synchronous {@code execute}:
     * a completed stage gives its outcome, a failed one a failed outcome, and one that is still waiting for another
     * thread is a failure (the synchronous entry never blocks; the engine uses the resumable one).
     */
    public static ActionOutcome now(CompletionStage<ActionOutcome> stage) {
        CompletableFuture<ActionOutcome> future = stage.toCompletableFuture();
        if (!future.isDone()) {
            return ActionOutcome.failed("the action needs another thread; run it through a flow");
        }
        try {
            return future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            return ActionOutcome.failed(String.valueOf(cause.getMessage()));
        }
    }
}
