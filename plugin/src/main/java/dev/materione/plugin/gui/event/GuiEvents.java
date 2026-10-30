package dev.materione.plugin.gui.event;

import dev.materione.behavior.rule.FilterFacts;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.List;
import java.util.Map;

/**
 * Fires the GUI triggers (logic.md §6.2): the one class that knows how a GUI event becomes a
 * {@link BehaviorEvent}. Each method builds the run context ({@link GuiContexts}), stamps the session
 * as the cancellation key of the runs it starts, supplies the facts the rules' filters compare with,
 * and hands the rules to the {@link RuleDispatcher}. It decides nothing: which rules, in which GUI,
 * for which click, are the callers' business.
 *
 * <p>Every method returns at once when there are no rules to run, so a GUI with no logic costs one
 * list check per event and builds no context.
 */
public final class GuiEvents {

    private final RuleDispatcher dispatcher;

    public GuiEvents(RuleDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Fires {@code gui.opening}.
     *
     * @return true when a rule cancelled the open ({@code cancel} or {@code stop: {cancel: true}})
     */
    public boolean opening(Viewer viewer, GuiSession session, RuntimeGuis.Live gui) {
        BehaviorEvent event = fire(GuiRuleSet.OPENING, gui.logic().rules(GuiRuleSet.OPENING),
                viewer, session, FilterFacts.NONE, Map.of());
        return event != null && event.isCancelRequested();
    }

    /** Fires {@code gui.open}: the window is showing. */
    public void opened(Viewer viewer, GuiSession session, RuntimeGuis.Live gui) {
        fire(GuiRuleSet.OPEN, gui.logic().rules(GuiRuleSet.OPEN), viewer, session, FilterFacts.NONE, Map.of());
    }

    /** Fires {@code gui.close} with the reason the session ended. {@link CloseReason#REMOVED} fires nothing. */
    public void closed(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, CloseReason reason) {
        if (!reason.fires()) {
            return;
        }
        fire(GuiRuleSet.CLOSE, gui.logic().rules(GuiRuleSet.CLOSE), viewer, session,
                key -> "reason".equals(key) ? reason.id() : null,
                Map.of("close", Map.of("reason", reason.id())));
    }

    /** Fires {@code gui.click_outside}: a click outside the window. */
    public void outside(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, ClickKind kind) {
        fire(GuiRuleSet.CLICK_OUTSIDE, gui.logic().rules(GuiRuleSet.CLICK_OUTSIDE), viewer, session,
                key -> "click".equals(key) ? kind.id() : null,
                GuiContexts.click(kind.id(), -1, 0));
    }

    /**
     * Fires the {@code gui.tick} rules due at {@code elapsed} ticks since the window opened: those
     * whose {@code every:} divides it.
     */
    public void tick(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, long elapsed) {
        for (Rule rule : gui.logic().rules(GuiRuleSet.TICK)) {
            int every = GuiRuleSet.everyOf(rule);
            if (every > 0 && elapsed % every == 0) {
                // the rule's own `every` is the fact its filter is compared with: it is due, so it matches
                String due = Integer.toString(every);
                fire(GuiRuleSet.TICK, List.of(rule), viewer, session, key -> "every".equals(key) ? due : null,
                        Map.of());
            }
        }
    }

    /**
     * Fires {@code gui.click} for one already-routed click: {@code rules} are the rules of the layer the
     * router chose (or the layer-less ones), and the engine applies each rule's own filters on top.
     *
     * @param layer the layer that took the click, or "" when none did
     */
    public void click(
            Viewer viewer, GuiSession session, List<Rule> rules, String layer, int slot, ClickKind kind, int key) {
        var extra = new java.util.LinkedHashMap<String, Object>(GuiContexts.click(kind.id(), slot, key));
        extra.put("layer", Map.of("id", layer));
        fire(GuiRuleSet.CLICK, rules, viewer, session, key2 -> switch (key2) {
            case "layer" -> layer.isEmpty() ? null : layer;
            case "slot" -> Integer.toString(slot);
            case "click" -> kind.id();
            default -> null;
        }, extra);
    }

    private BehaviorEvent fire(
            String trigger, List<Rule> rules, Viewer viewer, GuiSession session, FilterFacts facts,
            Map<String, Object> extra) {
        if (rules.isEmpty()) {
            return null;
        }
        BehaviorEvent event = new BehaviorEvent(
                trigger, null, GuiContexts.build(viewer, session, extra), null, viewer.player())
                .withCancelKey(session)
                .withFilterFacts(facts);
        dispatcher.fire(rules, event);
        return event;
    }
}
