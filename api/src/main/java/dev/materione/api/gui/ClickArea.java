package dev.materione.api.gui;

import java.util.Set;

/**
 * Where on the container screen a layer can be clicked (logic.md §6.2). A component declares it
 * through {@link CompiledGuiLayer#clickArea()}; the GUI compiler turns every layer's area into the
 * GUI's click map (slot to layers, topmost first), so the runtime resolves a click with one lookup
 * and no geometry.
 *
 * <p>Two shapes cover every component. A rectangle in GUI pixels (buttons, text, images) covers the
 * container slots whose 16 x 16 interior centre falls inside it. A slot set (item layers, slot
 * wells) covers exactly those container slots.
 */
public sealed interface ClickArea permits ClickArea.Rect, ClickArea.Slots, ClickArea.None {

    /** The layer is not clickable: no rule can target it and it never takes a click. */
    ClickArea NONE = new None();

    /**
     * A rectangle in GUI pixels, origin at the screen's top-left. The rectangle is the half-open
     * pixel range {@code [x, x + width) x [y, y + height)}; an empty rectangle covers nothing.
     */
    record Rect(int x, int y, int width, int height) implements ClickArea {}

    /** Exact container slots, indexed from 0 at the top-left, row by row. */
    record Slots(Set<Integer> slots) implements ClickArea {
        public Slots {
            slots = Set.copyOf(slots);
        }
    }

    /** Not clickable. */
    record None() implements ClickArea {}
}
