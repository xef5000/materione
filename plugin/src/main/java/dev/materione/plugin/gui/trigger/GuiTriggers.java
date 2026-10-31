package dev.materione.plugin.gui.trigger;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import dev.materione.plugin.gui.session.CloseReason;
import java.util.ArrayList;
import java.util.List;

/**
 * The triggers the GUI runtime fires (logic.md §6.2), as descriptions: their filters, the variables
 * they guarantee, and whether a rule may cancel them. Registered through the public logic API like an
 * addon's, so the compiler checks {@code ${root}} paths against these schemas and the studio
 * autocompletes from them (one declaration for both, D5). {@code gui.page} arrives with the list work.
 */
public final class GuiTriggers {

    private static final List<String> HOST = List.of("gui");
    private static final List<String> CLOSE_REASONS = java.util.Arrays.stream(CloseReason.values())
            .filter(CloseReason::fires).map(CloseReason::id).toList();

    private GuiTriggers() {}

    /** The eight triggers, in the order the editor lists them. */
    public static List<TriggerType> all() {
        return List.of(
                trigger(GuiRuleSet.OPENING, "GUI opening",
                        "A GUI is about to open for a player; a rule can cancel the open",
                        List.of(), List.of(), true),
                trigger(GuiRuleSet.OPEN, "GUI opened", "A GUI's window has opened for a player",
                        List.of(), List.of(), false),
                trigger(GuiRuleSet.CLOSE, "GUI closed",
                        "A GUI's session ended: the player closed it, a flow closed it, another GUI replaced it, "
                                + "or the player left",
                        List.of(ContextVariable.of("close.reason", ParameterType.STRING,
                                "Why it ended: " + String.join(", ", CLOSE_REASONS))),
                        List.of(filter("reason", ParameterType.ENUM, CLOSE_REASONS,
                                "Only for this reason: " + String.join(", ", CLOSE_REASONS), null)), false),
                trigger(GuiRuleSet.CLICK, "GUI click",
                        "The player clicks a slot of the GUI. The click is always cancelled; the topmost showing "
                                + "layer with a matching rule runs it",
                        List.of(ContextVariable.of("click.type", ParameterType.STRING, "The click kind: " + clickKinds()),
                                ContextVariable.of("click.slot", ParameterType.INTEGER, "The clicked slot, from 0"),
                                ContextVariable.of("click.key", ParameterType.INTEGER,
                                        "The hotbar key 1 to 9 of a number_key click, else 0"),
                                ContextVariable.of("layer.id", ParameterType.STRING,
                                        "The layer that took the click, blank for a rule with no layer filter")),
                        List.of(filter("layer", ParameterType.STRING, List.of(),
                                        "Only clicks on this layer (an id, or a list of ids)", null),
                                filter("slot", ParameterType.INTEGER, List.of(),
                                        "Only clicks on this container slot (a number, or a list)", 0.0),
                                filter("click", ParameterType.ENUM, ClickKind.ids(),
                                        "Only this kind of click (a kind, or a list)", null)),
                        false),
                trigger(GuiRuleSet.CLICK_OUTSIDE, "GUI outside click",
                        "The player clicks outside the GUI's window",
                        List.of(ContextVariable.of("click.type", ParameterType.STRING, "left or right"),
                                ContextVariable.of("click.slot", ParameterType.INTEGER, "Always -1")),
                        List.of(filter("click", ParameterType.ENUM, List.of("left", "right"),
                                "Only this side's click", null)),
                        false),
                trigger(GuiRuleSet.TICK, "GUI tick",
                        "Every so many ticks while the GUI is open; nothing runs when the GUI has no tick rule",
                        List.of(), List.of(requiredFilter("every", ParameterType.INTEGER,
                                "Run every this many ticks (20 is a second); at least 1", 1.0)),
                        false),
                trigger(GuiRuleSet.SLOT_CHANGE, "GUI input slot change",
                        "The player is about to put an item into, or take one out of, an input slot. The change "
                                + "happens unless a rule cancels it, which reverts it",
                        List.of(ContextVariable.of("slot.index", ParameterType.INTEGER, "The container slot, from 0"),
                                ContextVariable.of("slot.item", ParameterType.STRING,
                                        "The item the slot will hold (its id, blank when empty)"),
                                ContextVariable.of("slot.amount", ParameterType.INTEGER,
                                        "How many the slot will hold"),
                                ContextVariable.of("slot.previous", ParameterType.STRING,
                                        "The item the slot holds now (its id, blank when empty)")),
                        List.of(filter("layer", ParameterType.STRING, List.of(),
                                "Only changes to this input layer (an id, or a list of ids)", null)),
                        true),
                trigger(GuiRuleSet.CLICK_INVENTORY, "GUI inventory click",
                        "The player clicks in their own inventory while the GUI is open. A locked inventory "
                                + "cancels the click anyway; a free one lets it through unless a rule cancels it",
                        List.of(ContextVariable.of("click.type", ParameterType.STRING, "The click kind: " + clickKinds()),
                                ContextVariable.of("click.slot", ParameterType.INTEGER,
                                        "The clicked slot of the player's inventory"),
                                ContextVariable.of("click.key", ParameterType.INTEGER,
                                        "The hotbar key 1 to 9 of a number_key click, else 0"),
                                ContextVariable.of("slot.item", ParameterType.STRING,
                                        "The item the player clicked (its id, blank when empty)")),
                        List.of(filter("click", ParameterType.ENUM, ClickKind.ids(),
                                "Only this kind of click (a kind, or a list)", null)),
                        true));
    }

    private static String clickKinds() {
        return String.join(", ", ClickKind.ids());
    }

    /** The variables every GUI trigger guarantees: the player, the server, the GUI and its session. */
    private static List<ContextVariable> common() {
        return List.of(
                ContextVariable.of("player.name", ParameterType.STRING, "The player's name"),
                ContextVariable.of("player.uuid", ParameterType.STRING, "The player's UUID"),
                ContextVariable.of("player.gamemode", ParameterType.STRING, "survival, creative, adventure or spectator"),
                ContextVariable.of("player.sneaking", ParameterType.BOOLEAN, "Whether the player is sneaking"),
                ContextVariable.of("player.op", ParameterType.BOOLEAN, "Whether the player is an operator"),
                ContextVariable.of("player.health", ParameterType.DECIMAL, "The player's health"),
                ContextVariable.of("player.level", ParameterType.INTEGER, "The player's experience level"),
                ContextVariable.of("server.online", ParameterType.INTEGER, "Players online"),
                ContextVariable.of("server.max", ParameterType.INTEGER, "Player slots"),
                ContextVariable.of("gui.id", ParameterType.STRING, "The GUI's id, pack:name"),
                ContextVariable.of("gui.args.*", ParameterType.STRING, "An argument the GUI was opened with"),
                ContextVariable.of("gui.session.*", ParameterType.RAW, "A session variable of this open GUI"),
                ContextVariable.of("gui.page.*", ParameterType.RAW, "The page state of a list in this GUI"),
                ContextVariable.of("vars.*", ParameterType.RAW, "Variables written earlier in this run by set_var"));
    }

    private static TriggerType trigger(String id, String name, String description, List<ContextVariable> own,
            List<ParameterSchema> filters, boolean cancellable) {
        var context = new ArrayList<ContextVariable>(common());
        context.addAll(own);
        return new GuiTrigger(id, new TriggerDescription(
                SelfDescription.of(name, description), List.of(), HOST, filters, context, cancellable));
    }

    private static ParameterSchema filter(
            String id, ParameterType type, List<String> allowed, String description, Double min) {
        return schema(id, type, false, allowed, description, min);
    }

    private static ParameterSchema requiredFilter(String id, ParameterType type, String description, Double min) {
        return schema(id, type, true, List.of(), description, min);
    }

    private static ParameterSchema schema(
            String id, ParameterType type, boolean required, List<String> allowed, String description, Double min) {
        var schema = new ParameterSchema(id, type, required, null, description, allowed, null);
        if (min == null) {
            return schema;
        }
        return schema.withEditor(schema.editor().withRange(min, null, ""));
    }

    /** A trigger that is only its description. */
    private record GuiTrigger(String id, TriggerDescription description) implements TriggerType {
        @Override
        public TriggerDescription describe() {
            return description;
        }
    }
}
