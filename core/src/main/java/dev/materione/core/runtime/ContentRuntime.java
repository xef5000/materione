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

    /**
     * The pre-apply report (plugin.md §4.5): change counts per type,
     * removals by policy (M2 ships one policy — keep-flagged: the placed
     * instances stay and resolve to orphans, flagged for admin resolution),
     * and recipe enable/disable counts.
     */
    public record ApplyReport(
            int added, int removed, int changed,
            int blocksAdded, int blocksRemoved,
            int furnitureAdded, int furnitureRemoved,
            int recipesEnabled, int recipesDisabled,
            String packSha256) {

        /** Operator-facing summary lines (log + doctor). */
        public void lines(java.util.function.Consumer<String> out) {
            out.accept("items: +" + added() + " ~" + changed() + " -" + removed()
                    + " (removed stay keep-flagged)");
            if (blocksAdded() + blocksRemoved() > 0) {
                out.accept("blocks: +" + blocksAdded() + " -" + blocksRemoved());
            }
            if (furnitureAdded() + furnitureRemoved() > 0) {
                out.accept("furniture: +" + furnitureAdded() + " -" + furnitureRemoved());
            }
            out.accept("recipes: " + recipesEnabled() + " enabled, " + recipesDisabled() + " disabled");
        }
    }

    private volatile RuntimeRegistries registries = RuntimeRegistries.empty();
    private volatile String livePackSha256 = "";

    public RuntimeRegistries registries() {
        return registries;
    }

    public String livePackSha256() {
        return livePackSha256;
    }

    /**
     * The pre-apply report against the live state — computable BEFORE any
     * swap, so an operator surface can show it first (§4.5).
     */
    public ApplyReport diffAgainst(RuntimeRegistries shadow, String packSha256) {
        Objects.requireNonNull(shadow, "shadow");
        return diff(registries, shadow, packSha256);
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

        var liveBlocks = live.view(RuntimeBlocks.KEY);
        var shadowBlocks = shadow.view(RuntimeBlocks.KEY);
        int blocksAdded = countNew(liveBlocks == null ? null : liveBlocks.ids(),
                shadowBlocks == null ? null : shadowBlocks.ids());
        int blocksRemoved = countNew(shadowBlocks == null ? null : shadowBlocks.ids(),
                liveBlocks == null ? null : liveBlocks.ids());

        var liveFurniture = live.view(RuntimeFurnitures.KEY);
        var shadowFurniture = shadow.view(RuntimeFurnitures.KEY);
        int furnitureAdded = countNew(liveFurniture == null ? null : liveFurniture.ids(),
                shadowFurniture == null ? null : shadowFurniture.ids());
        int furnitureRemoved = countNew(shadowFurniture == null ? null : shadowFurniture.ids(),
                liveFurniture == null ? null : liveFurniture.ids());

        int recipesEnabled = 0;
        for (var recipe : shadow.recipes()) {
            if (live.recipe(recipe.id()).isEmpty()) {
                recipesEnabled++;
            }
        }
        int recipesDisabled = 0;
        for (var recipe : live.recipes()) {
            if (shadow.recipe(recipe.id()).isEmpty()) {
                recipesDisabled++;
            }
        }

        return new ApplyReport(added, removed, changed,
                blocksAdded, blocksRemoved, furnitureAdded, furnitureRemoved,
                recipesEnabled, recipesDisabled, packSha256);
    }

    /** Entries of {@code next} absent from {@code previous} (either set may be missing). */
    private static int countNew(java.util.Set<?> previous, java.util.Set<?> next) {
        if (next == null || next.isEmpty()) {
            return 0;
        }
        if (previous == null) {
            return next.size();
        }
        return (int) next.stream().filter(id -> !previous.contains(id)).count();
    }
}
