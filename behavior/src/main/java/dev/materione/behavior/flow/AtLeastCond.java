package dev.materione.behavior.flow;

import dev.materione.api.logic.LogicContext;
import java.util.List;

/**
 * {@code at_least: {count: n, of: [...]}}: at least {@code count} children hold (DeluxeMenus'
 * {@code minimum_requirements}). Lazy in both directions: it stops as soon as the count is reached,
 * and as soon as the children left cannot reach it.
 */
public record AtLeastCond(int count, List<Condition> of) implements Condition {

    public AtLeastCond {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        of = List.copyOf(of);
    }

    @Override
    public boolean test(LogicContext context) {
        int held = 0;
        int remaining = of.size();
        for (Condition child : of) {
            if (held >= count) {
                return true;
            }
            if (held + remaining < count) {
                return false;
            }
            remaining--;
            if (child.test(context)) {
                held++;
            }
        }
        return held >= count;
    }

    @Override
    public boolean readsLiveState() {
        return of.stream().anyMatch(Condition::readsLiveState);
    }

    @Override
    public String describe() {
        return "at_least(" + count + ", " + Conditions.describe(of) + ")";
    }
}
