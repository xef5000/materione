package dev.materione.plugin.gui.input;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.content.gui.CompiledGui;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The input slots of one live GUI and whether its viewer's inventory is locked (logic.md §6.5, §25).
 * Built once per live view from the compiled GUI; a window holds the same table as long as its view
 * does. A slot's {@code accept:} is compiled here, once, with the flow engine's own compiler.
 *
 * <p>The accept check is fail-closed: a condition that did not compile accepts nothing, and so does one
 * that throws while it is tested.
 */
public final class InputSlots {

    /** The trigger whose variables an accept condition may read. */
    public static final String SLOT_CHANGE = "gui.slot_change";

    /**
     * One input slot.
     *
     * @param slot the container slot
     * @param layer the input layer that claims it
     * @param accept the compiled {@code accept:}, or null when the layer accepts anything
     * @param acceptBroken true when the authored {@code accept:} did not compile; nothing is accepted
     */
    public record Input(int slot, String layer, Condition accept, boolean acceptBroken) {}

    /** A GUI with no input slots and a free viewer inventory: every GUI before #218. */
    public static final InputSlots NONE = new InputSlots(Map.of(), false);

    private final Map<Integer, Input> bySlot;
    private final boolean locked;

    private InputSlots(Map<Integer, Input> bySlot, boolean locked) {
        this.bySlot = Map.copyOf(bySlot);
        this.locked = locked;
    }

    /**
     * @param inputs the input slots, at most one entry per slot
     * @param locked whether the viewer's own inventory is locked
     */
    public static InputSlots of(Collection<Input> inputs, boolean locked) {
        var bySlot = new TreeMap<Integer, Input>();
        for (Input input : inputs) {
            bySlot.put(input.slot(), input);
        }
        return new InputSlots(bySlot, locked);
    }

    /**
     * Compiles the accept conditions of a compiled GUI's input slots against the live registries. An
     * accept that does not compile is kept as broken (the slot accepts nothing), never dropped.
     *
     * @param inputs the input slots, with each accept as authored
     * @param owner the GUI the slots belong to, for diagnostics
     * @param lookup the registries accept conditions resolve against; null when there is no flow engine
     * @param locked whether the viewer's own inventory is locked
     */
    public static InputSlots compile(
            Collection<CompiledGui.Input> inputs, NamespacedId owner, LogicLookup lookup, boolean locked) {
        var out = new ArrayList<Input>();
        for (CompiledGui.Input input : inputs) {
            if (input.accept() == null) {
                out.add(new Input(input.slot(), input.layer(), null, false));
            } else if (lookup == null) {
                out.add(new Input(input.slot(), input.layer(), null, true));
            } else {
                var compiled = FlowCompiler.compileCondition(owner, input.accept(), SLOT_CHANGE, lookup);
                out.add(new Input(input.slot(), input.layer(), compiled.condition().orElse(null),
                        compiled.condition().isEmpty()));
            }
        }
        return of(out, locked);
    }

    /**
     * Whether {@code input} accepts the item the context describes ({@code slot.item} and friends).
     * Fail-closed: a broken condition, or one that throws, refuses the item.
     */
    public static boolean accepts(Input input, LogicContext context) {
        if (input.acceptBroken()) {
            return false;
        }
        if (input.accept() == null) {
            return true;
        }
        try {
            return input.accept().test(context);
        } catch (RuntimeException failed) {
            return false;
        }
    }

    /** Whether the GUI has any input slot. */
    public boolean hasInputs() {
        return !bySlot.isEmpty();
    }

    /** Whether the slot is an input slot. */
    public boolean isInput(int slot) {
        return bySlot.containsKey(slot);
    }

    /** The input slot at {@code slot}, if it is one. */
    public Optional<Input> at(int slot) {
        return Optional.ofNullable(bySlot.get(slot));
    }

    /** Every input slot, in slot order. */
    public Collection<Input> all() {
        return bySlot.values();
    }

    /** Whether the viewer's own inventory is locked (logic.md §6.5). */
    public boolean locked() {
        return locked;
    }
}
