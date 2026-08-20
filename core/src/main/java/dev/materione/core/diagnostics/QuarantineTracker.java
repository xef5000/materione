package dev.materione.core.diagnostics;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime quarantine state (master §5.4): a quarantined resource is invisible
 * to players but visible in the editor and {@code /materione doctor} as
 * "broken, here's why" — never silently dropped. Empty at M0 by design; the
 * pipeline (M1) fills it.
 */
public final class QuarantineTracker {

    /** One quarantined resource and why. */
    public record Quarantined(NamespacedId resource, Diagnostic reason) {}

    private final List<Quarantined> quarantined = new ArrayList<>();

    public synchronized void add(NamespacedId resource, Diagnostic reason) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(reason, "reason");
        quarantined.add(new Quarantined(resource, reason));
    }

    public synchronized List<Quarantined> all() {
        return List.copyOf(quarantined);
    }

    public synchronized int size() {
        return quarantined.size();
    }

    public synchronized void clear() {
        quarantined.clear();
    }
}
