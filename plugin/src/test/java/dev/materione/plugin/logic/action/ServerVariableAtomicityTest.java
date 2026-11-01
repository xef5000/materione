package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.logic.Cancellation;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.flow.AllCond;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleFilters;
import dev.materione.behavior.rule.RuleOptions;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.RunBudget;
import dev.materione.behavior.run.RunOwner;
import dev.materione.behavior.run.RunScheduler;
import dev.materione.core.runtime.RuntimeVariables;
import dev.materione.plugin.logic.variable.ServerVariableStore;
import dev.materione.plugin.logic.variable.VariableService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ServerVariableAtomicityTest {
    private static final NamespacedId ID = NamespacedId.of("test", "total");
    private static final VariableDeclaration DECLARATION =
            new VariableDeclaration(ID, VariableScope.SERVER, VariableType.NUMBER, 0, "");

    private static VariableService service() {
        VariableStore player = new VariableStore() {
            @Override public VariableScope scope() { return VariableScope.PLAYER; }
            @Override public Optional<Object> read(VariableDeclaration declaration, UUID holder) {
                return Optional.empty();
            }
            @Override public boolean write(VariableDeclaration declaration, UUID holder, Object value) {
                return false;
            }
        };
        var server = new ServerVariableStore(Path.of("build", "atomicity-unwritten.json"), ignored -> {});
        return new VariableService(player, server, () -> new RuntimeVariables(Map.of(ID, DECLARATION)), ignored -> {});
    }

    @Test
    void arithmeticValidatesTheActualValueNotTheDeclarationDefault() {
        var declaration = new VariableDeclaration(ID, VariableScope.SERVER, VariableType.NUMBER, 1e308, "");
        var store = new ServerVariableStore(Path.of("build", "atomicity-unwritten.json"), ignored -> {});
        assertThat(store.write(declaration, null, -1e308)).isTrue();
        assertThat(store.mutate(declaration, null, VariableOp.ADD, 1e308).ok()).isTrue();
        assertThat(store.read(declaration, null)).contains(0d);
        assertThat(store.mutate(declaration, null, VariableOp.ADD, "not a number").ok()).isFalse();
        assertThat(store.read(declaration, null)).contains(0d);
        assertThat(store.write(declaration, null, 1e308)).isTrue();
        assertThat(store.mutate(declaration, null, VariableOp.ADD, 1e308).ok()).isFalse();
        assertThat(store.read(declaration, null)).contains(1e308);
        assertThat(store.mutate(declaration, null, VariableOp.ADD, Double.POSITIVE_INFINITY).ok()).isFalse();
        assertThat(store.read(declaration, null)).contains(1e308);
    }

    @Test
    void apiSetInterleavesBeforeAtomicAddWithoutBeingLostOrBlockedByOperand() throws Exception {
        var service = service();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Number operand = new Number() {
            @Override public double doubleValue() {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
                return 1;
            }
            @Override public int intValue() { return 1; }
            @Override public long longValue() { return 1; }
            @Override public float floatValue() { return 1; }
        };
        var context = new MapLogicContext(Map.of()).withVariables(service.bind(null));
        var params = new SetVarAction.Params(new SetVarAction.PersistentTarget(DECLARATION),
                VariableOp.ADD, new SetVarAction.Literal(operand));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var add = workers.submit(() -> new SetVarAction().executeResumable(params, context, ReadExecutor.INLINE)
                    .toCompletableFuture().join());
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var set = workers.submit(() -> service.api().set(ActorContext.console(), ID, null, 50d));
            assertThat(set.get(3, TimeUnit.SECONDS).ok()).isTrue();
            release.countDown();
            assertThat(add.get(3, TimeUnit.SECONDS).isOk()).isTrue();
            assertThat(service.api().get(ID, null)).contains(51d);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void serverSetAndAddFinishInlineDuringSynchronousPlayerPreEvent() {
        var service = service();
        var cancelled = new AtomicBoolean();
        var context = new MapLogicContext(Map.of()).withVariables(service.bind(null))
                .with(Cancellation.FACET, new Cancellation() {
                    @Override public void request() { cancelled.set(true); }
                    @Override public boolean requested() { return cancelled.get(); }
                });
        var path = StepPath.of("pre-event");
        var action = new SetVarAction();
        var set = new SetVarAction.Params(new SetVarAction.PersistentTarget(DECLARATION),
                VariableOp.SET, new SetVarAction.Literal(10));
        var add = new SetVarAction.Params(new SetVarAction.PersistentTarget(DECLARATION),
                VariableOp.ADD, new SetVarAction.Literal(2));
        var rule = new Rule(ID, "pre-event", new EventSelector("drop", Optional.empty()), new AllCond(List.of()),
                List.of(new ActionStep<>(path, "set_var", action, set, 1, 0),
                        new ActionStep<>(path, "set_var", action, add, 1, 0)),
                RuleOptions.none(), RuleFilters.none(), path);
        RunScheduler scheduler = new RunScheduler() {
            @Override public boolean canRunInline(Object anchor, Threading owner) {
                return owner == Threading.ANY || owner == Threading.PLAYER_REGION;
            }
            @Override public Handle schedule(Object anchor, Threading owner, long delay, Runnable task, Runnable drop) {
                throw new AssertionError("synchronous pre-event must not schedule");
            }
        };
        var engine = new FlowInterpreter(scheduler, RunBudget.DEFAULT, ignored -> {});
        engine.start(rule, context, RunOwner.of("player", "player"), () -> true);
        assertThat(cancelled).isFalse();
        assertThat(service.api().get(ID, null)).contains(12d);
    }
}
