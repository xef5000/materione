package dev.materione.plugin.gui.render;

import dev.materione.api.logic.LogicContext;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.logic.variable.VariableService;
import java.util.Map;
import org.bukkit.inventory.Inventory;

/**
 * The window renderer (logic.md §6.3, §9): renders a session's GUI for its viewer and paints the window. It
 * owns the one place a render meets the server: {@link #paint} writes the slots that changed since the last
 * paint and remembers the render on the session, so the next paint, the click router and the refresh clock
 * all read the same state.
 *
 * <p>All methods run on the viewer's thread.
 */
public final class GuiRenderer {

    private final SlotRenderer slots;
    private final VariableService variables;

    /** @param reporter where a problem the render worked around goes, rate-limited by the caller */
    public GuiRenderer(RenderReporter reporter) {
        this(reporter, null);
    }

    public GuiRenderer(RenderReporter reporter, VariableService variables) {
        this.slots = new SlotRenderer(reporter);
        this.variables = variables;
    }

    /**
     * Renders the GUI for the viewer without writing it anywhere.
     *
     * @param full true to re-read every layer (a reload or {@code gui.refresh}); false to re-read only the
     *     dynamic layers (the refresh clock)
     * @param ticks the window's clock
     */
    public Rendered render(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, boolean full, long ticks) {
        LogicContext context = GuiContexts.build(viewer, session, Map.of(), variables);
        return slots.render(gui, context, ticks, full ? null : session.rendered());
    }

    /**
     * Renders the GUI and writes the slots that changed since the last paint of this window, then remembers
     * the render on {@code session}.
     *
     * @param full as for {@link #render}
     */
    public void paint(Inventory inventory, Viewer viewer, GuiSession session, RuntimeGuis.Live gui, boolean full) {
        Rendered previous = session.rendered();
        Rendered next = render(viewer, session, gui, full, session.windowTicks());
        RenderDiff.apply(previous.slots(), next.slots(), (slot, appearance) ->
                inventory.setItem(slot, appearance == null ? null : AppearanceStacks.build(appearance)));
        session.remember(next);
    }
}
