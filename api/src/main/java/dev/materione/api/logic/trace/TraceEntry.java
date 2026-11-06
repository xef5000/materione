package dev.materione.api.logic.trace;

import java.util.Objects;

/**
 * One observable moment of one run, as a subscriber sees it (logic.md §7.6, §32).
 *
 * @param runId the run's id, unique per server start
 * @param resource the id of the resource that owns the rule (a GUI's {@code pack:id}), as the studio names it
 * @param rule the rule's id
 * @param player the player the run belongs to (a UUID text), or an empty string when it has none
 * @param path the step the entry is about, in the step-path scheme the diagnostics use
 *        ({@code behavior.2.do.0.then.1}); the rule's own path for run-level entries
 * @param kind what happened
 * @param detail a short human sentence (the failure's reason, the branch taken), or an empty string
 * @param atMillis when the entry was taken, in epoch milliseconds
 */
public record TraceEntry(
        long runId, String resource, String rule, String player, String path, TraceKind kind, String detail,
        long atMillis) {

    public TraceEntry {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        player = player == null ? "" : player;
        detail = detail == null ? "" : detail;
    }
}
