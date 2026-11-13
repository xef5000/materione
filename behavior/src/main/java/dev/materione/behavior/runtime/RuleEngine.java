package dev.materione.behavior.runtime;

import dev.materione.behavior.expr.Expressions;
import dev.materione.behavior.flow.ResumableConditions;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleOptions;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.RunOwner;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.ReadRetiredException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

/**
 * The ECA engine's rule gate (runtime-systems §2, D4): matches a fired event against rules, gates
 * each on its cooldown and charges, evaluates its rule-level condition and hands the survivors to
 * the {@link FlowInterpreter}, which runs the flow (logic.md §5). Everything about running steps,
 * threads, waits and budgets lives there; this class decides only <em>whether</em> a rule runs.
 *
 * <p>Threading: {@link #fire} runs wherever the event arrived (the region thread on Folia). The
 * flow starts on that thread and stays on it for as long as its steps allow; the interpreter hops
 * for a step that needs another thread and resumes parked runs through its
 * {@link dev.materione.behavior.run.RunScheduler}.
 *
 * <p>Failure honesty: a broken condition, action or evaluation degrades to a rate-limited
 * diagnostic (MTR-0023) and the rule, or the chain, carries on or stops safely, never a crash and
 * never spam (plugin.md §5).
 */
public final class RuleEngine {

    private final FlowInterpreter interpreter;
    private final CooldownStore cooldowns;
    private final LongSupplier clock;

    public RuleEngine(FlowInterpreter interpreter, CooldownStore cooldowns) {
        this(interpreter, cooldowns, System::currentTimeMillis);
    }

    /** @param clock epoch milliseconds the cooldown windows are read against; injectable for tests */
    public RuleEngine(FlowInterpreter interpreter, CooldownStore cooldowns, LongSupplier clock) {
        this.interpreter = interpreter;
        this.cooldowns = cooldowns;
        this.clock = clock;
    }

    /** The interpreter this engine feeds (cancellation, trace sink, budgets). */
    public FlowInterpreter interpreter() {
        return interpreter;
    }

    /**
     * Fires one event against a rule list. Rules run in definition order; each is gated
     * independently, so a skipped or failed rule never blocks its siblings.
     */
    public void fire(Iterable<Rule> rules, BehaviorEvent event) {
        var ordered = new ArrayList<Rule>();
        rules.forEach(ordered::add);
        var gate = interpreter.gateReads(ownerOf(event), event::synchronousPreEventOpen);
        try {
            fireNext(ordered, 0, clock.getAsLong(), event, gate);
        } finally {
            event.closePreEvent();
        }
    }

    private void fireNext(List<Rule> rules, int index, long now, BehaviorEvent event,
            FlowInterpreter.GateReads gate) {
        for (int i = index; i < rules.size(); i++) {
            if (!gate.executor().active()) {
                return;
            }
            Rule rule = rules.get(i);
            if (!rule.on().matches(event.eventType(), event.detail())
                    || !rule.filters().matches(event.filterFacts())) {
                continue;
            }
            String throttleKey = throttleKey(rule, event);
            // 0 when the rule may fire; otherwise the cooldown blocked it and only its on_cooldown flow may run
            long blockedMs = cooldowns.tryFireRemaining(throttleKey, now, rule.options().cooldownMs());
            if (blockedMs > 0) {
                if (rule.onCooldown().isEmpty()) {
                    continue;
                }
            } else if (!cooldowns.trySpendCharge(throttleKey + ":charges", rule.options().charges())) {
                continue;
            }
            var result = ResumableConditions.test(rule.condition(), event.context(), gate.executor())
                    .toCompletableFuture();
            if (!result.isDone()) {
                if (event.synchronousPreEventOpen()) {
                    event.requestCancel();
                    interpreter.reporter().failure(rule, rule.path().child("if"),
                            "condition failed: asynchronous read before synchronous event returns");
                    gate.close();
                    return;
                }
                if (!gate.park()) {
                    interpreter.reporter().failure(rule, rule.path().child("if"),
                            "condition failed: pending-read budget exhausted or gate cancelled");
                    gate.close();
                    return;
                }
                int next = i + 1;
                result.whenComplete((holds, failure) -> {
                    gate.resume();
                    if (!gate.executor().active()) {
                        return;
                    }
                    if (retired(failure)) {
                        gate.close();
                        return;
                    }
                    if (conditionResult(rule, event, holds, failure)) {
                        start(rule, event, gate, blockedMs);
                    }
                    fireNext(rules, next, now, event, gate);
                });
                return;
            }
            Boolean holds = null;
            Throwable failure = null;
            try {
                holds = result.join();
            } catch (CompletionException e) {
                failure = e.getCause();
            } catch (java.util.concurrent.CancellationException e) {
                failure = e;
            }
            if (retired(failure)) {
                gate.close();
                return;
            }
            if (!conditionResult(rule, event, holds, failure)) {
                continue;
            }

            start(rule, event, gate, blockedMs);
        }
        gate.close();
    }

    /**
     * Starts the rule's run. When its cooldown blocked it ({@code blockedMs > 0}), the run is its {@code on_cooldown}
     * flow, with {@code cooldown.remaining} set to the whole seconds left, rounded up (logic.md §16). Otherwise it is
     * the rule's own flow, exactly as before.
     */
    private static void start(Rule rule, BehaviorEvent event, FlowInterpreter.GateReads gate, long blockedMs) {
        if (blockedMs <= 0) {
            gate.start(rule, event.context());
            return;
        }
        long seconds = (blockedMs + 999L) / 1000L;
        gate.start(rule.cooldownRule(), event.contextWithRoot(RuleOptions.COOLDOWN_ROOT,
                Map.of(RuleOptions.REMAINING, (double) seconds)));
    }

    private static boolean retired(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure instanceof ReadRetiredException;
    }

    private boolean conditionResult(Rule rule, BehaviorEvent event, Boolean holds, Throwable e) {
        if (e != null) {
            while (e instanceof CompletionException && e.getCause() != null) {
                e = e.getCause();
            }
            // a condition that cannot be evaluated never lets the rule through
            if (e instanceof ReadUnavailableException && event.synchronousPreEventOpen()) {
                event.requestCancel();
            }
            interpreter.reporter().failure(rule, rule.path().child("if"), "condition failed: " + e.getMessage());
            return false;
        }
        return Boolean.TRUE.equals(holds);
    }

    /** Runs belong to the player (logic.md §5.2): keyed by the event's player, anchored to its entity. */
    private static RunOwner ownerOf(BehaviorEvent event) {
        Object uuid = event.evaluation().resolve(List.of("player", "uuid"));
        Object playerKey = Expressions.truthy(uuid) ? Expressions.stringify(uuid) : null;
        return new RunOwner(playerKey, event.anchor(), event.cancelKey());
    }

    /**
     * The scope key for cooldown/charge state: per-player keys embed the {@code player.uuid}
     * variable when present, global keys do not.
     */
    private static String throttleKey(Rule rule, BehaviorEvent event) {
        String base = rule.namedId().orElseGet(() -> rule.owner() + "#" + rule.on());
        RuleOptions options = rule.options();
        if (!RuleOptions.PER_GLOBAL.equals(options.scope())
                && Expressions.truthy(event.evaluation().resolve(List.of("player", "uuid")))) {
            Object uuid = event.evaluation().resolve(List.of("player", "uuid"));
            return base + "|" + Expressions.stringify(uuid);
        }
        return base;
    }
}
