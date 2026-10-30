package dev.materione.content.gui.logic;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.content.gui.GuiDefinition;
import java.util.List;

/**
 * Checks a GUI's rules in the diagnose stage (logic.md §10). The rules are compiled by the flow
 * engine, which {@code content} cannot see and which resolves actions and triggers against the live
 * registries, so the host supplies the check: the plugin compiles the rules with the engine's
 * compiler and adds the checks only a GUI can make (a rule that names a layer the GUI does not have).
 * Going through the diagnose stage is what makes the problems show up in the pack's diagnostics and
 * in {@code gui.validate} alike, each with its step path (the diagnostic target, {@code behavior.2.do.0}).
 *
 * <p>An implementation is pure and contained: it returns diagnostics and never throws for content,
 * and a GUI with no rules costs nothing.
 */
@FunctionalInterface
public interface GuiRuleChecker {

    /** Checks nothing: the checker of a host with no flow engine. */
    GuiRuleChecker NONE = gui -> List.of();

    /** The problems with {@code gui}'s rules, targeted at the offending node. */
    List<Diagnostic> check(GuiDefinition gui);
}
