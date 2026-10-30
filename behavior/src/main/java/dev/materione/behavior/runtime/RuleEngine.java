package dev.materione.behavior.runtime;

import dev.materione.behavior.expr.Expressions;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleOptions;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.RunOwner;
import java.util.List;

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

    public RuleEngine(FlowInterpreter interpreter, CooldownStore cooldowns) {
        this.interpreter = interpreter;
        this.cooldowns = cooldowns;
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
        long now = System.currentTimeMillis();
        for (Rule rule : rules) {
            if (!rule.on().matches(event.eventType(), event.detail())) {
                continue;
            }
            String throttleKey = throttleKey(rule, event);
            if (!cooldowns.tryFire(throttleKey, now, rule.options().cooldownMs())) {
                continue;
            }
            if (!cooldowns.trySpendCharge(throttleKey + ":charges", rule.options().charges())) {
                continue;
            }
            if (!conditionsMet(rule, event)) {
                continue;
            }
            interpreter.start(rule, event.context(), ownerOf(event));
        }
    }

    private boolean conditionsMet(Rule rule, BehaviorEvent event) {
        try {
            return rule.condition().test(event.context());
        } catch (RuntimeException e) {
            // a condition that cannot be evaluated never lets the rule through
            interpreter.reporter().failure(rule, rule.path().child("if"), "condition failed: " + e.getMessage());
            return false;
        }
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
