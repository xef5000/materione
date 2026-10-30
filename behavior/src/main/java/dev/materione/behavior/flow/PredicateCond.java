package dev.materione.behavior.flow;

import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.LogicContext;

/**
 * A registered predicate with the parameters its type parsed at load ({@code has_money: 250}).
 *
 * @param id the key as the author wrote it
 * @param type the resolved condition type
 * @param params the object {@code type.parse} produced
 * @param <P> the type's parameter object
 */
public record PredicateCond<P>(String id, ConditionType<P> type, P params) implements Condition {

    @Override
    public boolean test(LogicContext context) {
        return type.test(params, context);
    }

    @Override
    public boolean readsLiveState() {
        return true;
    }

    @Override
    public String describe() {
        return id + ": " + params;
    }
}
