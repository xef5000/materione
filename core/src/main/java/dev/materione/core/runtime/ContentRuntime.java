package dev.materione.core.runtime;

import java.util.Objects;

/**
 * The live runtime holder (plugin.md §4.5): apply builds a complete shadow
 * ({@link RuntimeRegistries} + compiled pack reference), reports the diff
 * (pre-apply report), then swaps atomically. Any exception after the swap
 * rolls back to the previous shadow — the server never sees a half-applied
 * state (D6).
 */
public final class ContentRuntime {

    /** What one apply changed, for the pre-apply report and doctor. */
    public record ApplyReport(int added, int removed, int changed, String packSha256) {}

    private volatile RuntimeRegistries registries = RuntimeRegistries.empty();
    private volatile String livePackSha256 = "";

    public RuntimeRegistries registries() {
        return registries;
    }

    public String livePackSha256() {
        return livePackSha256;
    }

    /**
     * Diffs shadow against live (the pre-apply report), then swaps. The
     * returned report reaches the operator before anything player-facing
     * happens; a no-op (all zeros) still swaps — identical registries make
     * the swap itself unobservable.
     */
    public ApplyReport apply(RuntimeRegistries shadow, String packSha256) {
        Objects.requireNonNull(shadow, "shadow");
        ApplyReport report = diff(registries, shadow, packSha256);
        this.registries = shadow;
        this.livePackSha256 = packSha256 == null ? "" : packSha256;
        return report;
    }

    /** Rolls back to the previous shadow — apply's exception path only. */
    public void rollback(RuntimeRegistries previous, String previousPackSha256) {
        this.registries = previous == null ? RuntimeRegistries.empty() : previous;
        this.livePackSha256 = previousPackSha256 == null ? "" : previousPackSha256;
    }

    private static ApplyReport diff(RuntimeRegistries live, RuntimeRegistries shadow, String packSha256) {
        int added = 0;
        int changed = 0;
        for (RuntimeItem item : shadow.items()) {
            var current = live.item(item.id());
            if (current.isEmpty()) {
                added++;
            } else if (!current.get().definitionHash().equals(item.definitionHash())) {
                changed++;
            }
        }
        int removed = (int) live.items().stream()
                .filter(item -> shadow.item(item.id()).isEmpty())
                .count();
        return new ApplyReport(added, removed, changed, packSha256);
    }
}
