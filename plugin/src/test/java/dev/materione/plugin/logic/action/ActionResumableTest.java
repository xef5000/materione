package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.ValueExpression;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.NamespacedId;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.run.RunReadExecutor;
import dev.materione.behavior.run.RunScheduler;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ActionResumableTest {
    @Test
    void messageWaitsForItsTemplateThenReturnsToThePlayerWithoutReplay() {
        var scheduler = new QueueScheduler();
        var reads = new RunReadExecutor(scheduler, "player");
        var count = new AtomicInteger();
        ExpressionFunction function = new ExpressionFunction() {
            @Override public String id() { return "hop"; }
            @Override public FunctionDescription describe() {
                return FunctionDescription.of(new SelfDescription("Hop", "", List.of(), "1"), ParameterType.STRING);
            }

            @Override public Object call(List<Object> arguments, LogicContext context) {
                throw new AssertionError("legacy callback was replayed");
            }
            @Override public CompletionStage<Object> callResumable(
                    List<Object> arguments, LogicContext context, ReadExecutor executor) {
                return executor.on(Threading.GLOBAL, () -> {
                    count.incrementAndGet();
                    return "live";
                });
            }
        };
        var compiler = EngineExpressions.with(name -> Optional.of(function));
        var params = new MessageAction.Params(compiler.template("value ${hop()}"), MessageAction.Target.PLAYER);
        var player = new PlayerFake();
        var result = new MessageAction().executeResumable(params, player.context(), reads).toCompletableFuture();

        assertThat(result).isNotDone();
        assertThat(player.calls).isEmpty();
        scheduler.runNext();
        assertThat(count).hasValue(1);
        assertThat(player.calls).isEmpty();
        scheduler.runNext();
        assertThat(result.join().isOk()).isTrue();
        assertThat(player.calls).containsExactly("message:value live");
        assertThat(count).hasValue(1);
    }

    @Test
    void computedVariableUsesTheLatestTargetValueAfterAnOwnerHop() {
        var scheduler = new QueueScheduler();
        var reads = new RunReadExecutor(scheduler, "player");
        var context = new MapLogicContext(Map.of());
        context.locals().put("count", 5d);
        ValueExpression expression = new ValueExpression() {
            @Override public String source() { return "hop()"; }
            @Override public Object evaluate(LogicContext ignored) {
                throw new AssertionError("legacy expression was replayed");
            }
            @Override public CompletionStage<Object> evaluateResumable(LogicContext ignored, ReadExecutor executor) {
                return executor.on(Threading.GLOBAL, () -> 3d);
            }
        };
        var params = new SetVarAction.Params(new SetVarAction.LocalTarget("count"), VariableOp.ADD,
                new SetVarAction.Computed(expression));
        var result = new SetVarAction().executeResumable(params, context, reads).toCompletableFuture();
        assertThat(result).isNotDone();
        context.locals().put("count", 9d);
        scheduler.runNext();
        assertThat(result).isNotDone();
        scheduler.runNext();
        assertThat(result.join().isOk()).isTrue();
        assertThat(context.locals()).containsEntry("count", 12d);
    }

    @Test
    void playerVariableReadsAndWritesLatestValueTogetherOnPlayerOwner() {
        var scheduler = new QueueScheduler();
        var reads = new RunReadExecutor(scheduler, "player");
        var declaration = new VariableDeclaration(NamespacedId.of("shop", "coins"),
                VariableScope.PLAYER, VariableType.NUMBER, 0, "");
        var current = new AtomicReference<Object>(5d);
        var context = new MapLogicContext(Map.of()).withVariables(new VariableBinding() {
            @Override public Optional<VariableDeclaration> declaration(NamespacedId id) {
                return Optional.of(declaration);
            }
            @Override public Optional<Object> stored(VariableDeclaration ignored) {
                assertThat(scheduler.current).isEqualTo(Threading.PLAYER_REGION);
                return Optional.of(current.get());
            }
            @Override public boolean store(VariableDeclaration ignored, Object value) {
                assertThat(scheduler.current).isEqualTo(Threading.PLAYER_REGION);
                current.set(value);
                return true;
            }
        });
        ValueExpression operand = new ValueExpression() {
            @Override public String source() { return "hop()"; }
            @Override public Object evaluate(LogicContext ignored) { throw new AssertionError("replayed"); }
            @Override public CompletionStage<Object> evaluateResumable(LogicContext ignored, ReadExecutor executor) {
                return executor.on(Threading.GLOBAL, () -> 3d);
            }
        };
        var params = new SetVarAction.Params(new SetVarAction.PersistentTarget(declaration),
                VariableOp.ADD, new SetVarAction.Computed(operand));
        var result = new SetVarAction().executeResumable(params, context, reads).toCompletableFuture();
        current.set(9d);
        scheduler.runNext();
        assertThat(current.get()).isEqualTo(9d);
        scheduler.runNext();
        assertThat(result.join().isOk()).isTrue();
        assertThat(current.get()).isEqualTo(12d);
    }

    @Test
    void concurrentServerVariableIncrementsSerializeOnGlobalOwner() throws Exception {
        var global = Executors.newSingleThreadExecutor(r -> new Thread(r, "global-owner"));
        var regions = Executors.newFixedThreadPool(4);
        var started = new CountDownLatch(1);
        try {
            ReadExecutor reads = new ReadExecutor() {
                @Override public <T> CompletionStage<T> on(Threading owner, java.util.function.Supplier<T> action) {
                    if (owner == Threading.GLOBAL) {
                        return CompletableFuture.supplyAsync(action, global);
                    }
                    return CompletableFuture.completedFuture(action.get());
                }
            };
            var declaration = new VariableDeclaration(NamespacedId.of("shop", "total"),
                    VariableScope.SERVER, VariableType.NUMBER, 0, "");
            var current = new AtomicReference<Object>(0d);
            var context = new MapLogicContext(Map.of()).withVariables(new VariableBinding() {
                @Override public Optional<VariableDeclaration> declaration(NamespacedId id) {
                    return Optional.of(declaration);
                }
                @Override public Optional<Object> stored(VariableDeclaration ignored) {
                    assertGlobalOwner();
                    return Optional.of(current.get());
                }
                @Override public boolean store(VariableDeclaration ignored, Object value) {
                    assertGlobalOwner();
                    current.set(value);
                    return true;
                }
            });
            var params = new SetVarAction.Params(new SetVarAction.PersistentTarget(declaration),
                    VariableOp.ADD, new SetVarAction.Literal(1));
            var workers = new java.util.ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 4; i++) {
                workers.add(CompletableFuture.runAsync(() -> {
                    try {
                        started.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    for (int n = 0; n < 100; n++) {
                        assertThat(new SetVarAction().executeResumable(params, context, reads)
                                .toCompletableFuture().join().isOk()).isTrue();
                    }
                }, regions));
            }
            started.countDown();
            CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertThat(current.get()).isEqualTo(400d);
        } finally {
            regions.shutdownNow();
            global.shutdownNow();
        }
    }

    private static void assertGlobalOwner() {
        assertThat(Thread.currentThread().getName()).isEqualTo("global-owner");
    }

    private static final class QueueScheduler implements RunScheduler {
        private record Task(Threading owner, Runnable run) {}
        private final ArrayDeque<Task> tasks = new ArrayDeque<>();
        private Threading current = Threading.PLAYER_REGION;

        @Override public boolean canRunInline(Object anchor, Threading owner) {
            return owner == Threading.ANY || owner == current;
        }
        @Override public Handle schedule(Object anchor, Threading owner, long ticks, Runnable task, Runnable retired) {
            var entry = new Task(owner, task);
            tasks.add(entry);
            return () -> tasks.remove(entry);
        }
        void runNext() {
            Task task = tasks.remove();
            current = task.owner();
            task.run().run();
        }
    }
}
