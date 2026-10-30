package dev.materione.behavior.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.logic.LogicFixture;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.ManualScheduler;
import dev.materione.behavior.run.RunBudget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Engine orchestration: matching, gating, deferral, chain continuation, cancel. */
class RuleEngineTest {

    private static final NamespacedId OWNER = NamespacedId.of("sample", "item");

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<String> ran = new ArrayList<>();
    private final CooldownStore cooldowns = new CooldownStore();
    private final LogicFixture logic = new LogicFixture()
            .action(LogicFixture.recording("a", Threading.PLAYER_REGION, ran))
            .action(LogicFixture.recording("b", Threading.PLAYER_REGION, ran))
            .action(LogicFixture.recording("later", Threading.PLAYER_REGION, ran))
            .action(LogicFixture.recording("worldly", Threading.GLOBAL, ran))
            .action(LogicFixture.recording("pure", Threading.ANY, ran))
            .action(LogicFixture.recording("boom", Threading.PLAYER_REGION, ran))
            .action(LogicFixture.recording("fine", Threading.PLAYER_REGION, ran))
            .action(LogicFixture.recording("fails", Threading.PLAYER_REGION, ran))
            .action(new LogicFixture.CancelFake());
    private final RuleEngine engine = new RuleEngine(
            new FlowInterpreter(scheduler, RunBudget.DEFAULT, diagnostics::add), cooldowns);

    private BehaviorEvent event(String type, String detail, Map<String, Object> root) {
        return new BehaviorEvent(type, detail, new MapLogicContext(root), null, "player-1");
    }

    /** Parses one rule through the real parser, so the engine runs exactly what the pipeline would hand it. */
    private List<Rule> rules(Object... declarations) {
        ParsedRules parsed = FlowCompiler.compile(OWNER, List.of(declarations), logic.lookup());
        assertThat(parsed.problems()).isEmpty();
        return parsed.rules();
    }

    private static Map<String, Object> rule(String on, List<String> conditions, List<Map<String, Object>> actions) {
        var rule = new LinkedHashMap<String, Object>();
        rule.put("on", on);
        if (!conditions.isEmpty()) {
            rule.put("if", conditions);
        }
        rule.put("do", actions);
        return rule;
    }

    private static Map<String, Object> action(String type, Object scalar) {
        return Map.of(type, scalar);
    }

    @Test
    void firesMatchingRulesInOrderAndSkipsNonMatching() {
        List<Rule> rules = rules(
                rule("click:left", List.of(), List.of(action("a", "x"))),
                rule("click:right", List.of(), List.of(action("b", "y"))),
                rule("break", List.of(), List.of(action("a", "z"))));

        engine.fire(rules, event("click", "left", Map.of()));

        assertThat(ran).containsExactly("a:x"); // r2 detail-mismatched, r3 type-mismatched
    }

    @Test
    void conditionsGateRules() {
        List<Rule> rules = rules(rule("consume", List.of("item.amount >= 5"), List.of(action("a", "go"))));

        engine.fire(rules, event("consume", "", Map.<String, Object>of("item", Map.of("amount", 2))));
        assertThat(ran).isEmpty();

        engine.fire(rules, event("consume", "", Map.<String, Object>of("item", Map.of("amount", 7))));
        assertThat(ran).containsExactly("a:go");
    }

    @Test
    void aFailingConditionExpressionSkipsTheRuleWithoutDiagnosticsSpam() {
        List<Rule> rules = rules(rule("drop", List.of("'a' * 3"), List.of(action("a", "x"))));

        engine.fire(rules, event("drop", "", Map.of()));
        assertThat(ran).isEmpty();
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code().code()).isEqualTo("MTR-0023");
    }

    @Test
    void oneBrokenActionNeverBreaksTheChain() {
        List<Rule> rules = rules(rule("pickup", List.of(), List.of(action("boom", "x"), action("fine", "y"))));

        engine.fire(rules, event("pickup", "", Map.of()));

        assertThat(ran).containsExactly("fine:y");
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).message()).contains("action 'boom' failed: action exploded");
    }

    @Test
    void aFailedOutcomeIsReportedLikeAnExceptionAndTheChainContinues() {
        List<Rule> rules = rules(rule("pickup", List.of(), List.of(action("fails", "x"), action("fine", "y"))));

        engine.fire(rules, event("pickup", "", Map.of()));

        assertThat(ran).containsExactly("fine:y");
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code().code()).isEqualTo("MTR-0023");
        assertThat(diagnostics.get(0).message()).contains("action 'fails' failed: no luck");
    }

    @Test
    void aGlobalActionHopsToTheGlobalThreadAndTheFlowKeepsItsWrittenOrder() {
        List<Rule> rules = rules(rule("place", List.of(), List.of(
                action("worldly", "w"), action("a", "p"), action("pure", "q"))));

        engine.fire(rules, event("place", "", Map.of()));
        assertThat(ran).isEmpty(); // the first step needs the global thread, so the run parked there

        scheduler.runPending();

        // strict order: global step, back to the player's thread for the next, then the pure one inline
        assertThat(ran).containsExactly("worldly:w", "a:p", "pure:q");
        assertThat(scheduler.history).extracting(ManualScheduler.Scheduled::thread)
                .containsExactly(Threading.GLOBAL, Threading.PLAYER_REGION);
        assertThat(scheduler.history).extracting(ManualScheduler.Scheduled::anchor)
                .containsOnly("player-1");
    }

    @Test
    void delayedActionsReallyWaitTheirTicksAndTheFlowWaitsWithThem() {
        Map<String, Object> delayed = new LinkedHashMap<>();
        delayed.put("later", "x");
        delayed.put("delay", 40);
        List<Rule> rules = rules(rule("click", List.of(), List.of(delayed, action("a", "after"))));

        engine.fire(rules, event("click", "", Map.of()));
        assertThat(ran).isEmpty();

        scheduler.advance(39);
        assertThat(ran).isEmpty();
        scheduler.advance(1);
        assertThat(ran).containsExactly("later:x", "a:after");
    }

    @Test
    void chanceZeroSkipsChanceOneAlwaysRuns() {
        Map<String, Object> never = new LinkedHashMap<>();
        never.put("a", "n");
        never.put("chance", 0.0);
        Map<String, Object> always = new LinkedHashMap<>();
        always.put("a", "y");
        always.put("chance", 1.0);
        List<Rule> rules = rules(
                rule("click", List.of(), List.of(never)),
                rule("click", List.of(), List.of(always)));

        for (int i = 0; i < 25; i++) {
            engine.fire(rules, event("click", "", Map.of()));
        }
        assertThat(ran).containsOnly("a:y");
        assertThat(ran).hasSize(25);
    }

    @Test
    void cooldownsGateRepeatedFiresPerScopeKey() {
        Map<String, Object> throttled = rule("click", List.of(), List.of(action("a", "x")));
        throttled.put("cooldown", 60_000);
        List<Rule> rules = rules(throttled);

        var root = new LinkedHashMap<String, Object>();
        root.put("player", Map.of("uuid", "u1"));
        engine.fire(rules, event("click", "right", root));
        engine.fire(rules, event("click", "right", root)); // inside the window

        assertThat(ran).hasSize(1);

        // a different player is a different scope key — not gated
        var other = new LinkedHashMap<String, Object>();
        other.put("player", Map.of("uuid", "u2"));
        engine.fire(rules, event("click", "right", other));
        assertThat(ran).hasSize(2);
    }

    @Test
    void chargesRunOutAndStayOut() {
        Map<String, Object> limited = rule("consume", List.of(), List.of(action("a", "x")));
        limited.put("charges", 2);
        List<Rule> rules = rules(limited);

        var root = new LinkedHashMap<String, Object>();
        root.put("player", Map.of("uuid", "u1"));
        BehaviorEvent use = event("consume", "", root);
        engine.fire(rules, use);
        engine.fire(rules, use);
        engine.fire(rules, use); // third charge does not exist

        assertThat(ran).hasSize(2);
    }

    @Test
    void failureLogsAreRateLimitedPerSubject() {
        List<Rule> rules = rules(rule("click", List.of(), List.of(action("boom", "x"))));

        for (int i = 0; i < 10; i++) {
            engine.fire(rules, event("click", "", Map.of()));
        }
        assertThat(diagnostics).hasSize(1); // ten failures, one diagnostic within the window
    }

    @Test
    void cancelRequestsSurfaceOnTheEventThroughTheCancellationFacet() {
        BehaviorEvent ev = event("click", "right", Map.of());
        engine.fire(rules(rule("click:right", List.of(), List.of(action("cancel", true)))), ev);
        assertThat(ev.isCancelRequested()).isTrue();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void actionsSeeTheFacetsAndLocalsOfTheEventContext() {
        ContextFacet<String> note = ContextFacet.of("test:note", String.class);
        MapLogicContext context = new MapLogicContext(Map.of()).with(note, "hello");
        BehaviorEvent ev = new BehaviorEvent("click", "", context, null, null);

        assertThat(ev.context().require(note)).isEqualTo("hello");
        ev.context().locals().put("count", 3d);
        assertThat(ev.evaluation().resolve(List.of("vars", "count"))).isEqualTo(3d);
    }
}
