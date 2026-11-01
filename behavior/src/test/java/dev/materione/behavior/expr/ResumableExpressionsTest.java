package dev.materione.behavior.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ResumableExpressionsTest {
    @Test
    void retainsTheLeftOperandAcrossAnOutstandingRead() {
        var pending = new CompletableFuture<Object>();
        var paths = new ArrayList<String>();
        var result = ResumableExpressions.evaluate(ExpressionParser.parse("first + second").tree(),
                reads(paths, pending)).toCompletableFuture();
        assertThat(paths).containsExactly("first", "second");
        assertThat(result).isNotDone();
        pending.complete(7.0);
        assertThat(result.join()).isEqualTo(9.0);
        assertThat(paths).containsExactly("first", "second");
    }

    @Test
    void shortCircuitsAndSelectsOnlyTheRequiredTernaryBranch() {
        var paths = new ArrayList<String>();
        var reads = reads(paths, new CompletableFuture<>());
        assertThat(ResumableExpressions.evaluate(ExpressionParser.parse("false && second").tree(), reads)
                .toCompletableFuture().join()).isEqualTo(false);
        assertThat(ResumableExpressions.evaluate(ExpressionParser.parse("true || second").tree(), reads)
                .toCompletableFuture().join()).isEqualTo(true);
        assertThat(ResumableExpressions.evaluate(ExpressionParser.parse("true ? first : second").tree(), reads)
                .toCompletableFuture().join()).isEqualTo(2.0);
        assertThat(paths).containsExactly("first");
    }

    @Test
    void repeatedReadsRemainDistinctAndAFailedReadStopsEvaluation() {
        var paths = new ArrayList<String>();
        var reads = new ResumableExpressions.Reads() {
            @Override public CompletionStage<Object> resolve(List<String> path) {
                paths.add(path.get(0));
                return path.get(0).equals("bad")
                        ? CompletableFuture.failedFuture(new IllegalStateException("retired"))
                        : CompletableFuture.completedFuture((double) paths.size());
            }
            @Override public CompletionStage<Object> call(ExpressionFunction function, List<Object> arguments) {
                throw new AssertionError("unexpected call");
            }
        };
        assertThat(ResumableExpressions.evaluate(ExpressionParser.parse("first + first").tree(), reads)
                .toCompletableFuture().join()).isEqualTo(3.0);
        var failed = ResumableExpressions.evaluate(ExpressionParser.parse("bad + later").tree(), reads)
                .toCompletableFuture();
        assertThatThrownBy(failed::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(paths).containsExactly("first", "first", "bad");
    }

    @Test
    void cancellationWhileParkedDoesNotEvaluateLaterOperands() {
        var pending = new CompletableFuture<Object>();
        var active = new AtomicBoolean(true);
        var paths = new ArrayList<String>();
        var reads = new ResumableExpressions.Reads() {
            @Override public boolean active() { return active.get(); }
            @Override public CompletionStage<Object> resolve(List<String> path) {
                paths.add(path.get(0));
                return pending;
            }
            @Override public CompletionStage<Object> call(ExpressionFunction function, List<Object> arguments) {
                throw new AssertionError("unexpected call");
            }
        };
        var result = ResumableExpressions.evaluate(ExpressionParser.parse("first + second").tree(), reads)
                .toCompletableFuture();
        active.set(false);
        pending.complete(1.0);
        assertThatThrownBy(result::join).hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(paths).containsExactly("first");
    }

    @Test
    void functionArgumentsAndRepeatedCallsRemainOrderedAcrossHops() {
        var pending = new CompletableFuture<Object>();
        var operations = new ArrayList<String>();
        ExpressionFunction function = new ExpressionFunction() {
            @Override public String id() { return "f"; }
            @Override public FunctionDescription describe() { throw new AssertionError("not used"); }
            @Override public Object call(List<Object> arguments, LogicContext context) {
                throw new AssertionError("the reader calls the function");
            }
        };
        var call = new ExprNode.Call("f", function, List.of(
                new ExprNode.Path(List.of("first")), new ExprNode.Path(List.of("second"))));
        var tree = new ExprNode.Binary("+", call, call);
        var reads = new ResumableExpressions.Reads() {
            int firstReads;
            @Override public CompletionStage<Object> resolve(List<String> path) {
                String name = path.get(0);
                operations.add(name);
                if (name.equals("second") && firstReads == 1) {
                    return pending;
                }
                if (name.equals("first")) {
                    firstReads++;
                }
                return CompletableFuture.completedFuture(2.0);
            }
            @Override public CompletionStage<Object> call(ExpressionFunction f, List<Object> arguments) {
                operations.add("call:" + arguments);
                return CompletableFuture.completedFuture(3);
            }
        };
        var result = ResumableExpressions.evaluate(tree, reads).toCompletableFuture();
        assertThat(operations).containsExactly("first", "second");
        pending.complete(1.0);
        assertThat(result.join()).isEqualTo(6.0);
        assertThat(operations).containsExactly("first", "second", "call:[2.0, 1.0]",
                "first", "second", "call:[2.0, 2.0]");
    }

    private static ResumableExpressions.Reads reads(List<String> paths, CompletableFuture<Object> pending) {
        return new ResumableExpressions.Reads() {
            @Override public CompletionStage<Object> resolve(List<String> path) {
                paths.add(path.get(0));
                return path.get(0).equals("first") ? CompletableFuture.completedFuture(2.0) : pending;
            }
            @Override public CompletionStage<Object> call(ExpressionFunction function, List<Object> arguments) {
                throw new AssertionError("unexpected call");
            }
        };
    }
}
