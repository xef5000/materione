package dev.materione.behavior.flow;

import dev.materione.api.logic.LogicContext;
import java.util.List;

/** {@code all: [...]}: every child holds; stops at the first that does not. Empty holds. */
public record AllCond(List<Condition> of) implements Condition {

    public AllCond {
        of = List.copyOf(of);
    }

    @Override
    public boolean test(LogicContext context) {
        for (Condition child : of) {
            if (!child.test(context)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean readsLiveState() {
        return of.stream().anyMatch(Condition::readsLiveState);
    }

    @Override
    public String describe() {
        return "all(" + Conditions.describe(of) + ")";
    }
}
