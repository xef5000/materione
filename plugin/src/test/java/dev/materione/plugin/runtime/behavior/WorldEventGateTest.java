package dev.materione.plugin.runtime.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.behavior.rule.EventSelector;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleOptions;
import dev.materione.behavior.flow.AllCond;
import dev.materione.behavior.flow.StepPath;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.runtime.RuntimeRegistries;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

/**
 * The cost of a world trigger nobody answers (plugin.md §9, logic.md §37): a handler's first act is one lookup
 * in the live shadow, and it returns before it reads the event, resolves a stack or builds a context. The test
 * proves it two ways: by counting what a handler touches when no rule subscribes (the shadow is read once and
 * nothing else happens, so the bridge, which would fail on this fake player, is never reached), and by timing the
 * gate against the budget.
 */
class WorldEventGateTest {

    /** The budget row of plugin.md §9: a world-event handler with no subscribing rule, per event. */
    private static final long GATE_BUDGET_NS = 250;

    private static Rule ruleOn(String trigger) {
        return new Rule(NamespacedId.of("fixture", "doc"), "r", new EventSelector(trigger, Optional.empty()),
                new AllCond(List.of()), List.of(), RuleOptions.none(), null, StepPath.root());
    }

    private static RuntimeRegistries shadowWith(WorldRules view) {
        return RuntimeRegistries.builder().view(WorldRules.KEY, view).build();
    }

    private static Player fakePlayer() {
        return Player.class.cast(Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "Alice";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakePlayer";
                    default -> null; // anything the bridge would read fails: the gate must stop before it
                }));
    }

    /**
     * A server whose only rule is the kill of zombies builds nothing for the death of a cow: the rule's filter is
     * checked against the event's facts before any stack, location or context is read. The suppliers here throw if
     * they are called, and the bridge behind them is absent, so reaching either one fails the test.
     */
    @Test
    void anEventThatNoRuleFiltersInReadsNothingFromTheWorld() {
        var zombiesOnly = new Rule(NamespacedId.of("fixture", "doc"), "zombies",
                new EventSelector(EventTypes.ENTITY_KILL, Optional.empty()), new AllCond(List.of()), List.of(),
                RuleOptions.none(), new dev.materione.behavior.rule.RuleFilters(Map.of("type", List.of("zombie"))),
                StepPath.root());
        var shadow = shadowWith(WorldRules.of(Map.of(NamespacedId.of("fixture", "doc"), List.of(zombiesOnly)), Map.of()));
        var events = new WorldEvents(null, () -> shadow, null);
        var touched = new AtomicInteger();

        events.fire(new WorldEvents.Fire(EventTypes.ENTITY_KILL, fakePlayer(),
                () -> {
                    touched.incrementAndGet();
                    return null;
                },
                Map.of("type", "cow"),
                () -> {
                    touched.incrementAndGet();
                    return Map.of();
                }, null,
                () -> {
                    touched.incrementAndGet();
                    return List.of();
                }, true));

        assertThat(events.subscribes(EventTypes.ENTITY_KILL)).isTrue(); // the gate is open: a rule answers kills
        assertThat(touched).hasValue(0);                                   // and the cow still read nothing
    }

    @Test
    void aShadowWithNoViewOrNoMatchingRuleSubscribesToNothing() {
        assertThat(new WorldEvents(null, () -> RuntimeRegistries.empty(), null).subscribes(EventTypes.ENTITY_HIT)).isFalse();
        var other = WorldRules.of(Map.of(NamespacedId.of("fixture", "doc"), List.of(ruleOn(EventTypes.PLAYER_JOIN))), Map.of());
        var events = new WorldEvents(null, () -> shadowWith(other), null);

        assertThat(events.subscribes(EventTypes.PLAYER_JOIN)).isTrue();
        assertThat(events.subscribes(EventTypes.ENTITY_HIT)).isFalse();
        assertThat(events.subscribes(EventTypes.PLAYER_QUIT)).isFalse();
    }

    @Test
    void aHandlerWithNoSubscribingRuleReadsTheShadowOnceAndTouchesNothingElse() {
        var reads = new AtomicInteger();
        var diagnostics = new DiagnosticsBusService(8);
        var shadow = shadowWith(WorldRules.empty());
        var listener = new WorldEventListener(new WorldEvents(null, () -> {
            reads.incrementAndGet();
            return shadow;
        }, null), diagnostics);
        Player player = fakePlayer();

        listener.onJoin(new PlayerJoinEvent(player, Component.empty()));
        listener.onQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        // one shadow read per event (the gate); a handler that went on would read the player, fail on the fake
        // one and publish a bridge failure, and would read the shadow a second time inside fire()
        assertThat(reads).hasValue(2);
        assertThat(diagnostics.total()).isZero();
    }

    @Test
    void aSubscribedTriggerGoesPastTheGateAndOnlyThatOne() {
        var reads = new AtomicInteger();
        var diagnostics = new DiagnosticsBusService(8);
        var shadow = shadowWith(WorldRules.of(
                Map.of(NamespacedId.of("fixture", "doc"), List.of(ruleOn(EventTypes.PLAYER_JOIN))), Map.of()));
        var listener = new WorldEventListener(new WorldEvents(null, () -> {
            reads.incrementAndGet();
            return shadow;
        }, null), diagnostics);
        Player player = fakePlayer();

        listener.onQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(reads).hasValue(1);
        assertThat(diagnostics.total()).isZero(); // quit has no rule: stopped at the gate

        listener.onJoin(new PlayerJoinEvent(player, Component.empty()));
        // join has a rule: the handler went on, and the fake player, which the bridge cannot read, was reported
        assertThat(diagnostics.total()).isEqualTo(1);
    }

    @Test
    void theGateIsWithinTheBudgetPerEvent() {
        var shadow = shadowWith(WorldRules.of(
                Map.of(NamespacedId.of("fixture", "doc"), List.of(ruleOn(EventTypes.PLAYER_JOIN))), Map.of()));
        var listener = new WorldEventListener(new WorldEvents(null, () -> shadow, null), new DiagnosticsBusService(8));
        Player player = fakePlayer();
        var quit = new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
        for (int i = 0; i < 200_000; i++) {
            listener.onQuit(quit); // warm up the JIT
        }
        int events = 2_000_000;
        long start = System.nanoTime();
        for (int i = 0; i < events; i++) {
            listener.onQuit(quit);
        }
        long perEvent = (System.nanoTime() - start) / events;

        System.out.println("MATERIONE_WORLD_GATE_PERF nanosPerEvent=" + perEvent + " budgetNanos=" + GATE_BUDGET_NS);
        assertThat(perEvent).as("a world-event handler nobody answers, per event").isLessThan(GATE_BUDGET_NS);
        assertThat(Duration.ofNanos(perEvent * events)).isLessThan(Duration.ofSeconds(1).plusMillis(500));
    }
}
