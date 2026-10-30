package dev.materione.plugin.gui.event;

import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.runtime.BehaviorEvent;

/**
 * Where the GUI runtime hands a fired trigger: the rule engine, in production
 * ({@code RuleEngine::fire}), and a recorder in the unit tests that exercise the session, opener and
 * router without a flow engine.
 */
@FunctionalInterface
public interface RuleDispatcher {

    /** Runs every rule of {@code rules} that answers {@code event}, in order. */
    void fire(Iterable<Rule> rules, BehaviorEvent event);
}
