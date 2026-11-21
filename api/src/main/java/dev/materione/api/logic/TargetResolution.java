package dev.materione.api.logic;

import java.util.List;
import java.util.Objects;

/**
 * What a {@link TargetResolver} found: the subjects, or the reason there are none. A selector that matches nobody
 * and a name nobody is online under both resolve to an empty list with a reason, and the action fails with it
 * (logic.md §39).
 *
 * @param targets the resolved subjects in the order the action runs on them; empty when none
 * @param reason why {@code targets} is empty; blank when it is not
 */
public record TargetResolution(List<ResolvedTarget> targets, String reason) {

    public TargetResolution {
        targets = targets == null ? List.of() : List.copyOf(targets);
        reason = reason == null ? "" : reason;
        if (targets.isEmpty() && reason.isBlank()) {
            throw new IllegalArgumentException("a resolution with no targets must say why");
        }
    }

    /** One subject. */
    public static TargetResolution of(ResolvedTarget target) {
        return new TargetResolution(List.of(Objects.requireNonNull(target, "target")), "");
    }

    /** Several subjects, or {@code emptyReason} when there are none. */
    public static TargetResolution of(List<ResolvedTarget> targets, String emptyReason) {
        return new TargetResolution(targets, emptyReason);
    }

    /** No subject, and why. */
    public static TargetResolution none(String reason) {
        return new TargetResolution(List.of(), reason);
    }
}
