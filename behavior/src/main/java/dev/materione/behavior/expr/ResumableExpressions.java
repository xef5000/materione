package dev.materione.behavior.expr;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;

/**
 * Ordered evaluation of an expression whose individual reads may complete on another owner.
 * The supplied reader owns scheduling, retirement and cancellation; this class never blocks.
 */
public final class ResumableExpressions {

    /** A reader must complete each stage on the thread on which evaluation may continue. */
    public interface Reads {
        /** False once the owning run has been cancelled or retired. */
        default boolean active() {
            return true;
        }

        CompletionStage<Object> resolve(List<String> path);

        CompletionStage<Object> call(ExpressionFunction function, List<Object> arguments);
    }

    private ResumableExpressions() {}

    /** Reads the run's player variables and opt-in functions through its owner executor. */
    public static Reads reads(LogicContext context, ReadExecutor executor) {
        ReadExecutor scoped = executor.captureOrigin();
        return new Reads() {
            @Override
            public boolean active() {
                return scoped.active();
            }

            @Override
            public CompletionStage<Object> resolve(List<String> path) {
                Threading owner = path.size() > 1 && path.get(0).equals("vars") && path.get(1).equals("player")
                        ? Threading.PLAYER_REGION : Threading.ANY;
                return scoped.on(owner, () -> context.resolve(path));
            }

            @Override
            public CompletionStage<Object> call(ExpressionFunction function, List<Object> arguments) {
                return function.callResumable(arguments, context, scoped);
            }
        };
    }

    /** Evaluates once, retaining already evaluated operands while a read is outstanding. */
    public static CompletionStage<Object> evaluate(ExprNode node, Reads reads) {
        try {
            return evaluateNode(node, reads);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static CompletionStage<Object> evaluateNode(ExprNode node, Reads reads) {
        if (!reads.active()) {
            return CompletableFuture.failedFuture(new CancellationException("evaluation cancelled"));
        }
        if (node instanceof ExprNode.Num value) {
            return completed(value.value());
        }
        if (node instanceof ExprNode.Str value) {
            return completed(value.value());
        }
        if (node instanceof ExprNode.Bool value) {
            return completed(value.value());
        }
        if (node instanceof ExprNode.Null) {
            return completed(null);
        }
        if (node instanceof ExprNode.Path path) {
            return reads.resolve(path.segments());
        }
        if (node instanceof ExprNode.Unary unary) {
            return evaluate(unary.operand(), reads)
                    .thenApply(value -> Expressions.applyUnary(unary.operator(), value));
        }
        if (node instanceof ExprNode.Ternary ternary) {
            return evaluate(ternary.condition(), reads).thenCompose(value -> evaluate(
                    Expressions.truthy(value) ? ternary.ifTrue() : ternary.ifFalse(), reads));
        }
        if (node instanceof ExprNode.Binary binary) {
            return evaluate(binary.left(), reads).thenCompose(left -> {
                if (binary.operator().equals("&&") && !Expressions.truthy(left)) {
                    return completed(false);
                }
                if (binary.operator().equals("||") && Expressions.truthy(left)) {
                    return completed(true);
                }
                return evaluate(binary.right(), reads).thenApply(right -> switch (binary.operator()) {
                    case "&&", "||" -> Expressions.truthy(right);
                    default -> Expressions.applyBinary(binary.operator(), left, right);
                });
            });
        }
        if (node instanceof ExprNode.Call call) {
            return arguments(call.arguments(), reads, 0, new ArrayList<>())
                    .thenCompose(args -> reads.active()
                            ? reads.call(call.function(), args)
                            : CompletableFuture.failedFuture(new CancellationException("evaluation cancelled")))
                    .thenApply(value -> value instanceof Number number && !(value instanceof Double)
                            ? number.doubleValue() : value);
        }
        return CompletableFuture.failedFuture(new ExpressionException("unknown expression node " + node.getClass()));
    }

    private static CompletionStage<List<Object>> arguments(
            List<ExprNode> nodes, Reads reads, int index, List<Object> values) {
        if (index == nodes.size()) {
            return CompletableFuture.completedFuture(Collections.unmodifiableList(values));
        }
        return evaluate(nodes.get(index), reads).thenCompose(value -> {
            values.add(value);
            return arguments(nodes, reads, index + 1, values);
        });
    }

    private static CompletionStage<Object> completed(Object value) {
        return CompletableFuture.completedFuture(value);
    }
}
