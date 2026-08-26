package dev.materione.web;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import dev.materione.core.pipeline.ReloadScope;

/**
 * The host side of the live preview loop (master §9.4): after an editor
 * saves, the transport asks the host to run a targeted reload over exactly
 * the touched files ({@code ReloadScope.FileSet}) and reports how it landed.
 * Implemented in {@code plugin/} on top of PipelineService; web/ only sees
 * this seam, so the protocol stays testable without a server.
 */
public interface ReloadTrigger {

    /** Queues one targeted reload; completes when the apply stage finished. */
    CompletableFuture<Outcome> reloadFiles(Set<java.nio.file.Path> files);

    /**
     * Queues a whole-scope reload (the manual {@code pipeline.reload} op and
     * per-pack actions). Hosts that only implement file-set reloads may leave
     * the default: callers get a clean rejected outcome instead of an error.
     */
    default CompletableFuture<Outcome> reloadScope(ReloadScope scope) {
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        future.complete(Outcome.rejected());
        return future;
    }

    /** What a reload did — enough for an activity feed line. */
    record Outcome(boolean accepted, boolean noop, int itemsLive, int quarantined) {

        public static Outcome rejected() {
            return new Outcome(false, true, -1, -1);
        }
    }
}
