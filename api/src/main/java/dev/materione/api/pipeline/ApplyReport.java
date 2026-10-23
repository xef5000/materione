package dev.materione.api.pipeline;

import java.util.function.Consumer;

/**
 * What one apply did to the live runtime (master §6.4): the diff-driven
 * counts computed before the shadow-swap and reported after it. The pre-apply
 * variant lists what the swap *will* do — removals by policy and
 * restart-required changes are surfaced here, never guessed.
 *
 * @param added           live items added
 * @param removed         live items removed (placements keep-flagged)
 * @param changed         live items whose definitions changed
 * @param blocksAdded     custom blocks added
 * @param blocksRemoved   custom blocks removed
 * @param furnitureAdded  furniture added
 * @param furnitureRemoved furniture removed
 * @param recipesEnabled  recipes enabled by this apply
 * @param recipesDisabled recipes disabled by this apply
 * @param packSha256      the pack bytes this report describes
 */
public record ApplyReport(
        int added, int removed, int changed,
        int blocksAdded, int blocksRemoved,
        int furnitureAdded, int furnitureRemoved,
        int recipesEnabled, int recipesDisabled,
        String packSha256) {

    /** Operator-facing summary lines (log + doctor). */
    public void lines(Consumer<String> out) {
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
