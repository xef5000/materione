package dev.materione.behavior.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.rule.ActionSpec;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import org.junit.jupiter.api.Test;

/** Engine orchestration: matching, gating, deferral, chain continuation, cancel. */
class RuleEngineTest {

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<String> deferred = new ArrayList<>();
    private final CooldownStore cooldowns = new CooldownStore();
    private RuleEngine engine = new RuleEngine(
            (anchor, delayTicks, task) -> {
                deferred.add(delayTicks + ":" + (anchor == null ? "-" : anchor));
                task.run(); // synchronous continuation keeps tests deterministic
            },
            diagnostics::add,
            cooldowns);

    private BehaviorEvent event(String type, String detail, Map<String, Object> root) {
        return new BehaviorEvent(type, detail, new EvaluationContext(root), null, "player-1");
    }

    private Rule rule(String id, String selector, List<String> conditions, List<ActionSpec> actions) {
        return new Rule(
                NamespacedId.of("sample", "item"),
                id,
                new dev.materione.behavior.rule.EventSelector(
                        selector.split(":")[0],
                        selector.contains(":")
                                ? java.util.Optional.of(selector.substring(selector.indexOf(':') + 1))
                                : java.util.Optional.empty()),
                conditions.stream()
                        .map(dev.materione.behavior.expr.ExpressionParser::parse)
                        .map(dev.materione.behavior.expr.Expression::tree)
                        .toList(),
                actions,
                RuleOptions.none());
    }

    private static ActionSpec action(String type, Object scalar) {
        return new ActionSpec(type, Map.of("value", scalar), 1.0, 0);
    }

    @Test
    void firesMatchingRulesInOrderAndSkipsNonMatching() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("a", true, ran));
        engine.register(new RecordingExecutor("b", true, ran));
        List<Rule> rules = List.of(
                rule("r1", "click:left", List.of(), List.of(action("a", "x"))),
                rule("r2", "click:right", List.of(), List.of(action("b", "y"))),
                rule("r3", "break", List.of(), List.of(action("a", "z"))));

        engine.fire(rules, event("click", "left", Map.of()));

        assertThat(ran).containsExactly("a:x"); // r2 detail-mismatched, r3 type-mismatched
    }

    @Test
    void conditionsGateRules() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("a", true, ran));
        List<Rule> rules = List.of(rule("r", "consume", List.of("item.amount >= 5"),
                List.of(action("a", "go"))));

        engine.fire(rules, event("consume", "", Map.<String, Object>of("item", Map.of("amount", 2))));
        assertThat(ran).isEmpty();

        engine.fire(rules, event("consume", "", Map.<String, Object>of("item", Map.of("amount", 7))));
        assertThat(ran).containsExactly("a:go");
    }

    @Test
    void aFailingConditionExpressionSkipsTheRuleWithoutDiagnosticsSpam() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("a", true, ran));
        List<Rule> rules = List.of(rule("r", "drop", List.of("'a' * 3"), List.of(action("a", "x"))));

        engine.fire(rules, event("drop", "", Map.of()));
        assertThat(ran).isEmpty();
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code().code()).isEqualTo("MTR-0023");
    }

    @Test
    void oneBrokenActionNeverBreaksTheChain() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("boom", true, ran)); // throws on run
        engine.register(new RecordingExecutor("fine", true, ran));
        List<Rule> rules = List.of(rule("r", "pickup", List.of(), List.of(
                action("boom", "x"), action("fine", "y"))));

        engine.fire(rules, event("pickup", "", Map.of()));

        assertThat(ran).containsExactly("fine:y");
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).message()).contains("action 'boom' failed");
    }

    @Test
    void unknownActionTypesAreDiagnosedNotFatal() {
        engine.register(new RecordingExecutor("known", true, new ArrayList<>()));
        List<Rule> rules = List.of(rule("r", "click", List.of(),
                List.of(action("mystery", "x"))));

        engine.fire(rules, event("click", "", Map.of()));

        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).message()).contains("unknown action type 'mystery'");
    }

    @Test
    void mustDeferExecutorsRouteThroughTheDeferredRunnerWithAnchor() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("worldly", false, ran));
        List<Rule> rules = List.of(rule("r", "place", List.of(), List.of(action("worldly", "w"))));

        engine.fire(rules, event("place", "", Map.of()));

        // deferred with the anchor and zero delay, but still executed by our sync runner
        assertThat(deferred).containsExactly("0:player-1");
        assertThat(ran).containsExactly("worldly:w");
    }

    @Test
    void delayedActionsCarryTheirTickCount() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("later", true, ran));
        List<Rule> rules = List.of(rule("r", "click", List.of(), List.of(
                new ActionSpec("later", Map.of("value", "x"), 1.0, 40))));

        engine.fire(rules, event("click", "", Map.of()));

        assertThat(deferred).containsExactly("40:player-1");
        assertThat(ran).containsExactly("later:x");
    }

    @Test
    void chanceZeroSkipsChanceOneAlwaysRuns() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("a", true, ran));
        List<Rule> rules = List.of(
                rule("never", "click", List.of(),
                        List.of(new ActionSpec("a", Map.of("value", "n"), 0.0, 0))),
                rule("always", "click", List.of(),
                        List.of(new ActionSpec("a", Map.of("value", "y"), 1.0, 0))));

        for (int i = 0; i < 25; i++) {
            engine.fire(rules, event("click", "", Map.of()));
        }
        assertThat(ran).containsOnly("a:y");
        SequencedCollection<String> check = ran;
        assertThat(check.size()).isEqualTo(25);
    }

    @Test
    void cooldownsGateRepeatedFiresPerScopeKey() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("a", true, ran));
        Rule throttled = new Rule(
                NamespacedId.of("sample", "item"),
                "throttled",
                new dev.materione.behavior.rule.EventSelector("click", java.util.Optional.empty()),
                List.of(),
                List.of(action("a", "x")),
                new RuleOptions(60_000L, null, RuleOptions.PER_PLAYER));

        var root = new LinkedHashMap<String, Object>();
        root.put("player", Map.of("uuid", "u1"));
        engine.fire(List.of(throttled), event("click", "right", root));
        engine.fire(List.of(throttled), event("click", "right", root)); // inside the window

        assertThat(ran).hasSize(1);

        // a different player is a different scope key — not gated
        var other = new LinkedHashMap<String, Object>();
        other.put("player", Map.of("uuid", "u2"));
        engine.fire(List.of(throttled), event("click", "right", other));
        assertThat(ran).hasSize(2);
    }

    @Test
    void chargesRunOutAndStayOut() {
        List<String> ran = new ArrayList<>();
        engine.register(new RecordingExecutor("a", true, ran));
        Rule limited = new Rule(
                NamespacedId.of("sample", "item"),
                "limited",
                new dev.materione.behavior.rule.EventSelector("consume", java.util.Optional.empty()),
                List.of(),
                List.of(action("a", "x")),
                new RuleOptions(0, 2, RuleOptions.PER_PLAYER));

        var root = new LinkedHashMap<String, Object>();
        root.put("player", Map.of("uuid", "u1"));
        BehaviorEvent use = event("consume", "", root);
        engine.fire(List.of(limited), use);
        engine.fire(List.of(limited), use);
        engine.fire(List.of(limited), use); // third charge does not exist

        assertThat(ran).hasSize(2);
    }

    @Test
    void failureLogsAreRateLimitedPerSubject() {
        engine.register(new RecordingExecutor("boom", true, new ArrayList<>()));
        List<Rule> rules = List.of(rule("r", "click", List.of(), List.of(action("boom", "x"))));

        for (int i = 0; i < 10; i++) {
            engine.fire(rules, event("click", "", Map.of()));
        }
        assertThat(diagnostics).hasSize(1); // ten failures, one diagnostic within the window
    }

    @Test
    void cancelRequestsSurfaceOnTheEvent() {
        engine.register(new CancelExecutor());
        BehaviorEvent ev = event("click", "right", Map.of());
        engine.fire(List.of(rule("r", "click:right", List.of(), List.of(action("cancel", true)))), ev);
        assertThat(ev.isCancelRequested()).isTrue();
    }

    // --- fixtures ---------------------------------------------------------------

    /** Records invocations as {@code type:value} or throws when named "boom". */
    private record RecordingExecutor(String name, boolean syncSafe, List<String> ran) implements ActionExecutor {
        @Override
        public String type() {
            return name;
        }

        @Override
        public void execute(ActionSpec action, BehaviorEvent event) {
            if (name.equals("boom")) {
                throw new IllegalStateException("executor exploded");
            }
            ran.add(name + ":" + action.scalarParam());
        }
    }

    private static final class CancelExecutor implements ActionExecutor {
        @Override
        public String type() {
            return "cancel";
        }

        @Override
        public boolean syncSafe() {
            return true;
        }

        @Override
        public void execute(ActionSpec action, BehaviorEvent event) {
            event.requestCancel();
        }
    }
}
