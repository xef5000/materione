package dev.materione.plugin.logic.support;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Resolves several {@link Quantity}s of one step before the step touches the world (logic.md §41). */
public final class Quantities {

    private Quantities() {}

    /**
     * Each quantity resolved once, in order, so a template is rendered (and its reads made) before the effect begins.
     * A null entry stays null.
     */
    public static CompletionStage<Quantity[]> resolve(LogicContext context, ReadExecutor reads, Quantity... in) {
        Quantity[] out = new Quantity[in.length];
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < in.length; i++) {
            int slot = i;
            if (in[slot] == null) {
                continue;
            }
            chain = chain.thenCompose(ignored -> in[slot].resolveResumable(context, reads))
                    .thenAccept(resolved -> out[slot] = resolved);
        }
        return chain.thenApply(ignored -> out);
    }

    /** The quantity of an optional parameter, or null when the author did not write it. */
    public static Quantity orNull(Optional<Quantity> quantity) {
        return quantity.orElse(null);
    }
}
