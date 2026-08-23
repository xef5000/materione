package dev.materione.behavior.runtime;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.behavior.expr.ExprNode;
import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.expr.Expressions;
import dev.materione.behavior.rule.ActionSpec;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.rule.RuleOptions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The ECA engine core (runtime-systems §2, D4): matches fired events against
 * rules, gates on cooldowns/charges, evaluates conditions and runs action
 * chains. Threading: {@link #fire} runs wherever the event arrived (region
 * thread on Folia); actions declare sync-safe vs must-defer, and deferral
 * plus explicit delays route through the {@link DeferredRunner}.
 *
 * <p>Failure honesty: one broken action or evaluation degrades to a
 * rate-limited diagnostic (MTR-0023) and the chain continues — never a
 * crash, never spam (plugin.md §5).
 */
public final class RuleEngine {

    /** Minimum milliseconds between two MTR-0023 diagnostics for the same rule. */
    private static final long FAILURE_LOG_INTERVAL_MS = 1000;

    private final Map<String, ActionExecutor> executors = new ConcurrentHashMap<>();
    private final DeferredRunner deferred;
    private final Consumer<Diagnostic> diagnostics;
    private final CooldownStore cooldowns;

    /** Per-rule last-failure log time (rate limiting). */
    private final Map<String, Long> lastFailureLog = new ConcurrentHashMap<>();

    public RuleEngine(DeferredRunner deferred, Consumer<Diagnostic> diagnostics, CooldownStore cooldowns) {
        this.deferred = deferred;
        this.diagnostics = diagnostics;
        this.cooldowns = cooldowns;
    }

    /** Registers an executor; later registrations of the same type replace earlier ones. */
    public void register(ActionExecutor executor) {
        executors.put(executor.type(), executor);
    }

    public int registeredExecutorCount() {
        return executors.size();
    }

    /**
     * Fires one event against a rule list. Rules run in definition order;
     * each is gated independently — a skipped or failed rule never blocks its
     * siblings.
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
            if (!conditionsMet(rule, event.context(), event)) {
                continue;
            }
            runActions(rule.actions(), event);
        }
    }

    private boolean conditionsMet(Rule rule, EvaluationContext context, BehaviorEvent event) {
        for (ExprNode condition : rule.conditions()) {
            try {
                if (!Expressions.truthy(Expressions.evaluate(condition, context))) {
                    return false;
                }
            } catch (RuntimeException e) {
                reportFailure(rule.namedId().orElse(rule.owner().toString()),
                        "condition failed: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private void runActions(Iterable<ActionSpec> actions, BehaviorEvent event) {
        for (ActionSpec action : actions) {
            ActionExecutor executor = executors.get(action.type());
            if (executor == null) {
                reportFailure(event, "unknown action type '" + action.type() + "'");
                continue;
            }
            if (action.chance() < 1.0
                    && java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= action.chance()) {
                continue;
            }
            if (action.delayTicks() > 0 || !executor.syncSafe()) {
                deferred.defer(event.anchor(), action.delayTicks(), () -> executeSafely(executor, action, event));
            } else {
                executeSafely(executor, action, event);
            }
        }
    }

    private void executeSafely(ActionExecutor executor, ActionSpec action, BehaviorEvent event) {
        try {
            executor.execute(action, event);
        } catch (RuntimeException e) {
            reportFailure(event, "action '" + action.type() + "' failed: " + e.getMessage());
        }
    }

    /**
     * The scope key for cooldown/charge state: per-player keys embed the
     * {@code player.uuid} variable when present, global keys do not.
     */
    private static String throttleKey(Rule rule, BehaviorEvent event) {
        String base = rule.namedId().orElseGet(() -> rule.owner() + "#" + rule.on());
        RuleOptions options = rule.options();
        if (!RuleOptions.PER_GLOBAL.equals(options.scope())
                && Expressions.truthy(event.context().resolve(java.util.List.of("player", "uuid")))) {
            Object uuid = event.context().resolve(java.util.List.of("player", "uuid"));
            return base + "|" + Expressions.stringify(uuid);
        }
        return base;
    }

    /** Rate-limited failure diagnostic (MTR-0023): at most one per rule per second. */
    public void reportFailure(BehaviorEvent event, String problem) {
        reportFailure(event.eventType() + "@" + event.detail(), problem);
    }

    public void reportFailure(String subject, String problem) {
        long now = System.currentTimeMillis();
        Long last = lastFailureLog.get(subject);
        if (last != null && now - last < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastFailureLog.put(subject, now);
        diagnostics.accept(new Diagnostic(
                dev.materione.core.diagnostics.DiagnosticCodes.ACTION_FAILED,
                Severity.WARN,
                subject + ": " + problem,
                null, null,
                "behavior continues despite the failure; repeated reports are rate-limited"));
    }
}
