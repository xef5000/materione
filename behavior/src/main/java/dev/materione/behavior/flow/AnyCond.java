package dev.materione.behavior.flow;

import dev.materione.api.logic.LogicContext;
import java.util.List;

/** {@code any: [...]}: some child holds; stops at the first that does. Empty never holds. */
public record AnyCond(List<Condition> of) implements Condition {

    public AnyCond {
        of = List.copyOf(of);
    }

    @Override
    public boolean test(LogicContext context) {
        for (Condition child : of) {
            if (child.test(context)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean readsLiveState() {
        return of.stream().anyMatch(Condition::readsLiveState);
    }

    @Override
    public String describe() {
        return "any(" + Conditions.describe(of) + ")";
    }
}
