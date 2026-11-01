package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.behavior.run.ManualScheduler;
import dev.materione.behavior.run.RunReadExecutor;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class ResumableEvaluationTest {
    @Test
    void compositeConditionKeepsOneOriginAcrossSequentialChildren() {
        var scheduler = new ManualScheduler();
        var reads = new RunReadExecutor(scheduler, "player");
        var calls = new ArrayList<String>();
        ExpressionFunction global = new ExpressionFunction() {
            @Override public String id() { return "global"; }
            @Override public FunctionDescription describe() {
                return FunctionDescription.of(new SelfDescription("Global", "", List.of(), "1"), ParameterType.BOOLEAN);
            }
            @Override public Object call(List<Object> args, LogicContext context) { throw new AssertionError(); }
            @Override public CompletionStage<Object> callResumable(
                    List<Object> args, LogicContext context, ReadExecutor executor) {
                return executor.on(Threading.GLOBAL, () -> { calls.add("global"); return true; });
            }
        };
        ExpressionFunction legacy = new ExpressionFunction() {
            @Override public String id() { return "legacy"; }
            @Override public FunctionDescription describe() {
                return FunctionDescription.of(new SelfDescription("Legacy", "", List.of(), "1"), ParameterType.BOOLEAN);
            }
            @Override public Object call(List<Object> args, LogicContext context) {
                assertThat(scheduler.current).isEqualTo(Threading.PLAYER_REGION);
                calls.add("legacy");
                return true;
            }
        };
        var condition = new dev.materione.behavior.flow.AllCond(List.of(
                new dev.materione.behavior.flow.ExpressionCond("global",
                        new dev.materione.behavior.expr.ExprNode.Call("global", global, List.of())),
                new dev.materione.behavior.flow.ExpressionCond("legacy",
                        new dev.materione.behavior.expr.ExprNode.Call("legacy", legacy, List.of()))));
        var result = dev.materione.behavior.flow.ResumableConditions
                .test(condition, new MapLogicContext(Map.of()), reads).toCompletableFuture();
        scheduler.runPending();
        assertThat(result.join()).isTrue();
        assertThat(calls).containsExactly("global", "legacy");
    }

    @Test
    void legacyFunctionReturnsToPlayerOwnerAfterGlobalArgument() {
        var scheduler = new ManualScheduler();
        var reads = new RunReadExecutor(scheduler, "player");
        var facet = ContextFacet.of("test:player", String.class);
        var context = new MapLogicContext(Map.of()).with(facet, "player-value");
        ExpressionFunction global = new ExpressionFunction() {
            @Override public String id() { return "global_read"; }
            @Override public FunctionDescription describe() {
                return FunctionDescription.of(new SelfDescription("Global", "", List.of(), "1"), ParameterType.STRING);
            }
            @Override public Object call(List<Object> arguments, LogicContext ignored) {
                throw new AssertionError("legacy global callback");
            }
            @Override public CompletionStage<Object> callResumable(
                    List<Object> arguments, LogicContext ignored, ReadExecutor executor) {
                return executor.on(Threading.GLOBAL, () -> {
                    assertThat(scheduler.current).isEqualTo(Threading.GLOBAL);
                    return "live";
                });
            }
        };
        ExpressionFunction legacy = new ExpressionFunction() {
            @Override public String id() { return "legacy"; }
            @Override public FunctionDescription describe() {
                return FunctionDescription.of(new SelfDescription("Legacy", "",
                        List.of(ParameterSchema.of("arg", ParameterType.STRING, true)), "1"), ParameterType.STRING);
            }
            @Override public Object call(List<Object> arguments, LogicContext ctx) {
                assertThat(scheduler.current).isEqualTo(Threading.PLAYER_REGION);
                return arguments.get(0) + ":" + ctx.facet(facet).orElseThrow();
            }
        };
        var expression = EngineExpressions.with(name -> switch (name) {
            case "global_read" -> Optional.of(global);
            case "legacy" -> Optional.of(legacy);
            default -> Optional.empty();
        }).expression("legacy(global_read())");
        var result = expression.evaluateResumable(context, reads).toCompletableFuture();
        assertThat(result).isNotDone();
        scheduler.runPending();
        assertThat(result.join()).isEqualTo("live:player-value");
        assertThat(scheduler.history).extracting(ManualScheduler.Scheduled::thread)
                .containsExactly(Threading.GLOBAL, Threading.PLAYER_REGION);
    }

    @Test
    void globalOriginTemplateRestoresGlobalForLegacyFunctionAfterPlayerOperand() {
        var scheduler = new ManualScheduler();
        scheduler.current = Threading.GLOBAL;
        var reads = new RunReadExecutor(scheduler, "player");
        var facet = ContextFacet.of("test:global", String.class);
        var id = NamespacedId.of("shop", "coins");
        var declaration = new VariableDeclaration(id, VariableScope.PLAYER, VariableType.NUMBER, 0, "");
        var context = new MapLogicContext(Map.of()).with(facet, "global-value")
                .withVariables(new VariableBinding() {
                    @Override public Optional<VariableDeclaration> declaration(NamespacedId asked) {
                        return asked.equals(id) ? Optional.of(declaration) : Optional.empty();
                    }
                    @Override public Optional<Object> stored(VariableDeclaration ignored) {
                        assertThat(scheduler.current).isEqualTo(Threading.PLAYER_REGION);
                        return Optional.of(7d);
                    }
                    @Override public boolean store(VariableDeclaration ignored, Object value) { return false; }
                });
        ExpressionFunction legacy = new ExpressionFunction() {
            @Override public String id() { return "legacy"; }
            @Override public FunctionDescription describe() {
                return FunctionDescription.of(new SelfDescription("Legacy", "",
                        List.of(ParameterSchema.of("arg", ParameterType.DECIMAL, true)), "1"), ParameterType.STRING);
            }
            @Override public Object call(List<Object> arguments, LogicContext ctx) {
                assertThat(scheduler.current).isEqualTo(Threading.GLOBAL);
                return arguments.get(0) + ":" + ctx.facet(facet).orElseThrow();
            }
        };
        var template = EngineExpressions.with(name -> Optional.of(legacy))
                .template("${legacy(vars.player.shop.coins)}");
        var result = template.renderResumable(context, reads).toCompletableFuture();
        assertThat(result).isNotDone();
        scheduler.runPending();
        assertThat(result.join()).isEqualTo("7.0:global-value");
        assertThat(scheduler.history).extracting(ManualScheduler.Scheduled::thread)
                .containsExactly(Threading.PLAYER_REGION, Threading.GLOBAL);
    }

    @Test
    void unknownOriginFailsClosedWithoutCallingLegacyFunction() {
        var scheduler = new ManualScheduler();
        scheduler.current = Threading.ANY;
        var reads = new RunReadExecutor(scheduler, "player");
        ExpressionFunction legacy = new ExpressionFunction() {
            @Override public String id() { return "legacy"; }
            @Override public FunctionDescription describe() {
                return FunctionDescription.of(new SelfDescription("Legacy", "", List.of(), "1"), ParameterType.STRING);
            }
            @Override public Object call(List<Object> arguments, LogicContext context) {
                throw new AssertionError("unknown owner was guessed");
            }
        };
        var expression = EngineExpressions.with(name -> Optional.of(legacy)).expression("legacy()");
        var result = expression.evaluateResumable(new MapLogicContext(Map.of()), reads).toCompletableFuture();
        assertThatThrownBy(result::join).hasCauseInstanceOf(ReadUnavailableException.class);
        assertThat(scheduler.pending()).isZero();
    }

    @Test
    void expressionAndTemplateEvaluateLivePlayerValuesInOrder() {
        var scheduler = new ManualScheduler();
        scheduler.current = Threading.GLOBAL;
        var executor = new RunReadExecutor(scheduler, "player");
        var calls = new ArrayList<Threading>();
        var id = NamespacedId.of("shop", "coins");
        var declaration = new VariableDeclaration(id, VariableScope.PLAYER, VariableType.NUMBER, 0, "");
        var context = new MapLogicContext(Map.of()).withVariables(new VariableBinding() {
            @Override public Optional<VariableDeclaration> declaration(NamespacedId asked) {
                return asked.equals(id) ? Optional.of(declaration) : Optional.empty();
            }
            @Override public Optional<Object> stored(VariableDeclaration ignored) {
                calls.add(scheduler.current);
                return Optional.of((double) calls.size());
            }
            @Override public boolean store(VariableDeclaration ignored, Object value) { return false; }
        });
        var template = EngineExpressions.INSTANCE.template(
                "before ${vars.player.shop.coins}, then ${vars.player.shop.coins}");
        var result = template.renderResumable(context, executor).toCompletableFuture();
        assertThat(result).isNotDone();
        scheduler.runPending();
        assertThat(result.join()).isEqualTo("before 1, then 2");
        assertThat(calls).containsExactly(Threading.PLAYER_REGION, Threading.PLAYER_REGION);

        calls.clear();
        var expression = EngineExpressions.INSTANCE.expression("vars.player.shop.coins + 3");
        var value = expression.evaluateResumable(context, executor).toCompletableFuture();
        assertThat(value).isNotDone();
        scheduler.runPending();
        assertThat(value.join()).isEqualTo(4.0);
    }
}
