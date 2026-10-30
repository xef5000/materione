package dev.materione.behavior.flow;

import dev.materione.api.logic.LogicContext;

/** {@code not: <condition>}. A child that throws makes the whole condition false, not true. */
public record NotCond(Condition inner) implements Condition {

    @Override
    public boolean test(LogicContext context) {
        return !inner.test(context);
    }

    @Override
    public boolean readsLiveState() {
        return inner.readsLiveState();
    }

    @Override
    public String describe() {
        return "not(" + inner.describe() + ")";
    }
}
