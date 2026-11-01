package dev.materione.behavior.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.ExpressionFunction;
import dev.materione.api.logic.FunctionDescription;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.flow.FlowFixture;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.runtime.BehaviorEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FlowContinuationTest {
    private final FlowFixture fixture = new FlowFixture();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final FlowInterpreter interpreter = new FlowInterpreter(scheduler, RunBudget.DEFAULT, ignored -> {});
    private final AtomicInteger reads = new AtomicInteger();
    private final List<String> rendered = new ArrayList<>();

    FlowContinuationTest() {
        ExpressionFunction hop = new ExpressionFunction() {
            @Override public String id() { return "hop"; }
            @Override public FunctionDescription describe() {
                return FunctionDescription.of(new SelfDescription("Hop", "", List.of(), "1"), ParameterType.DECIMAL);
            }
            @Override public Object call(List<Object> args, LogicContext context) {
                throw new AssertionError("legacy read must not be replayed");
            }
            @Override public CompletionStage<Object> callResumable(
                    List<Object> args, LogicContext context, ReadExecutor executor) {
                return executor.on(Threading.GLOBAL, () -> {
                    reads.incrementAndGet();
                    return 3d;
                });
            }
        };
        fixture.logic.registries().registry(RegistryKeys.EXPRESSION_FUNCTIONS, ExpressionFunction.class)
                .register(NamespacedId.of("materione", "hop"), hop, hop.describe().self(), "test");
        fixture.logic.action(new ActionType<TemplateText>() {
            @Override public String id() { return "async_say"; }
            @Override public ActionDescription describe() {
                return new ActionDescription(new SelfDescription("Async say", "",
                        List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1"),
                        LogicCategory.PLAYER, "text");
            }
            @Override public Threading threading() { return Threading.PLAYER_REGION; }
            @Override public TemplateText parse(ParamReader params) { return params.template("text"); }
            @Override public ActionOutcome execute(TemplateText text, LogicContext context) {
                throw new AssertionError("legacy action must not be replayed");
            }
            @Override public CompletionStage<ActionOutcome> executeResumable(
                    TemplateText text, LogicContext context, ReadExecutor executor) {
                return text.renderResumable(context, executor).thenCompose(value ->
                        executor.on(Threading.PLAYER_REGION, () -> {
                            rendered.add(value);
                            return ActionOutcome.OK;
                        }));
            }
        });
    }

    private FlowRun start(String body, String flows) {
        var parsed = fixture.compile("- on: click\n  do:\n" + body, flows);
        assertThat(parsed.problems()).isEmpty();
        return interpreter.start(parsed.rules().get(0), new MapLogicContext(Map.of()),
                RunOwner.of("u", "anchor"));
    }

    @Test
    void conditionsActionsAndCallArgumentsResumeOnceInOrder() {
        FlowRun run = start("""
                    - a: before
                    - if: "hop() == 3"
                      then: [ { b: gated } ]
                    - async_say: "value ${hop()}"
                    - call: {flow: sub, with: {x: "${hop()}"}}
                    - c: after
                """, "sub: [ { async_say: 'arg ${args.x}' } ]");
        assertThat(run.isParked()).isTrue();
        assertThat(fixture.ran).containsExactly("a:before");
        scheduler.runPending();
        assertThat(run.outcome()).isEqualTo(RunOutcome.FINISHED);
        assertThat(fixture.ran).containsExactly("a:before", "b:gated", "c:after");
        assertThat(rendered).containsExactly("value 3", "arg 3");
        assertThat(reads).hasValue(3);
    }

    @Test
    void cancellationDropsPendingReadWithoutRepeatingEarlierEffects() {
        FlowRun run = start("    - a: before\n    - async_say: '${hop()}'\n    - b: never\n", "sub: []");
        assertThat(interpreter.cancelPlayer("u")).isEqualTo(1);
        scheduler.runPending();
        assertThat(run.outcome()).isEqualTo(RunOutcome.CANCELLED);
        assertThat(fixture.ran).containsExactly("a:before");
        assertThat(rendered).isEmpty();
        assertThat(reads).hasValue(0);
    }

    @Test
    void retirementDropsThePendingReadAndCannotRunTheLaterAction() {
        FlowRun run = start("    - a: before\n    - async_say: '${hop()}'\n    - b: never\n", "sub: []");
        scheduler.dropAll();
        scheduler.dropAll();
        assertThat(run.outcome()).isEqualTo(RunOutcome.DROPPED);
        assertThat(fixture.ran).containsExactly("a:before");
        assertThat(rendered).isEmpty();
        assertThat(reads).hasValue(0);
    }

    @Test
    void synchronousPreEventFailsClosedBeforeAReadHop() {
        var parsed = fixture.compile("- on: click\n  do:\n    - async_say: '${hop()}'");
        assertThat(parsed.problems()).isEmpty();
        var event = new BehaviorEvent("click", "", new MapLogicContext(Map.of()), null, "anchor")
                .withSynchronousPreEvent();
        FlowRun run = interpreter.start(parsed.rules().get(0), event.context(), RunOwner.of("u", "anchor"),
                event::synchronousPreEventOpen);
        assertThat(run.outcome()).isEqualTo(RunOutcome.ABORTED);
        assertThat(event.isCancelRequested()).isTrue();
        assertThat(scheduler.pending()).isZero();
        assertThat(reads).hasValue(0);
    }
}
