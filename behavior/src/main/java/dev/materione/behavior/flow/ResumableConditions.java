package dev.materione.behavior.flow;

import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.expr.Expressions;
import dev.materione.behavior.expr.ResumableExpressions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Evaluates condition trees in authored order without repeating a completed child. */
public final class ResumableConditions {
    private ResumableConditions() {}

    public static CompletionStage<Boolean> test(Condition condition, LogicContext context, ReadExecutor executor) {
        if (!executor.active()) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("run ended"));
        }
        return switch (condition) {
            case ExpressionCond expression -> ResumableExpressions.evaluate(
                    expression.tree(), ResumableExpressions.reads(context, executor)).thenApply(Expressions::truthy);
            case PredicateCond<?> predicate -> predicate(predicate, context, executor);
            case AllCond all -> all(all.of(), 0, context, executor);
            case AnyCond any -> any(any.of(), 0, context, executor);
            case NotCond not -> test(not.inner(), context, executor).thenApply(value -> !value);
            case AtLeastCond minimum -> atLeast(minimum.of(), minimum.count(), 0, 0, context, executor);
        };
    }

    private static <P> CompletionStage<Boolean> predicate(
            PredicateCond<P> predicate, LogicContext context, ReadExecutor executor) {
        return executor.on(Threading.PLAYER_REGION,
                        () -> predicate.type().testResumable(predicate.params(), context, executor))
                .thenCompose(stage -> stage);
    }

    private static CompletionStage<Boolean> all(
            List<Condition> conditions, int index, LogicContext context, ReadExecutor executor) {
        if (index == conditions.size()) {
            return CompletableFuture.completedFuture(true);
        }
        return test(conditions.get(index), context, executor).thenCompose(value -> value
                ? all(conditions, index + 1, context, executor) : CompletableFuture.completedFuture(false));
    }

    private static CompletionStage<Boolean> any(
            List<Condition> conditions, int index, LogicContext context, ReadExecutor executor) {
        if (index == conditions.size()) {
            return CompletableFuture.completedFuture(false);
        }
        return test(conditions.get(index), context, executor).thenCompose(value -> value
                ? CompletableFuture.completedFuture(true) : any(conditions, index + 1, context, executor));
    }

    private static CompletionStage<Boolean> atLeast(
            List<Condition> conditions, int needed, int index, int held, LogicContext context, ReadExecutor executor) {
        if (held >= needed) {
            return CompletableFuture.completedFuture(true);
        }
        if (held + conditions.size() - index < needed) {
            return CompletableFuture.completedFuture(false);
        }
        return test(conditions.get(index), context, executor).thenCompose(value ->
                atLeast(conditions, needed, index + 1, held + (value ? 1 : 0), context, executor));
    }
}
