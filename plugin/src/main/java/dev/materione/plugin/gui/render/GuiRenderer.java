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
    private final TitleRenderer titles;
    private final VariableService variables;

    /** @param reporter where a problem the render worked around goes, rate-limited by the caller */
    public GuiRenderer(RenderReporter reporter) {
        this(reporter, null);
    }

    public GuiRenderer(RenderReporter reporter, VariableService variables) {
        this.slots = new SlotRenderer(reporter);
        this.titles = new TitleRenderer(reporter);
        this.variables = variables;
    }

    /**
     * Renders the GUI for the viewer. The one state it writes is the page each of the GUI's lists showed, which the
     * session keeps (logic.md §6.7): it is recorded before the title is laid out, so a title that reads
     * {@code gui.page.<list>.count} sees this render's count. Nothing is written to the window.
     *
     * @param full true to re-read every layer (a reload or {@code gui.refresh}); false to re-read only the
     *     dynamic layers (the refresh clock)
     * @param ticks the window's clock
     */
    public Rendered render(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, boolean full, long ticks) {
        LogicContext context = GuiContexts.build(viewer, session, Map.of(), variables);
        Rendered slotRender = slots.render(gui, context, ticks, full ? null : session.rendered());
        recordPages(session, slotRender.lists());
        Rendered rendered;
        if (gui.visuals() == null) {
            rendered = new Rendered(slotRender.slots(), slotRender.hidden(), slotRender.resolved(), gui.title(), Map.of(),
                    slotRender.lists());
        } else {
            var title = titles.render(gui.id(), gui.rows(), gui.visuals(), context, ticks);
            var hidden = new java.util.HashSet<>(slotRender.hidden());
            hidden.addAll(title.hidden());
            rendered = new Rendered(slotRender.slots(), hidden, slotRender.resolved(), title.title(), title.coverage(),
                    slotRender.lists());
        }
        var owned = gui.inputs().effective(gui.clicks(), rendered);
        var slots = new java.util.HashMap<>(rendered.slots());
        var shown = new java.util.HashMap<>(rendered.lists().shown());
        owned.all().forEach(input -> {
            slots.remove(input.slot());
            shown.remove(input.slot()); // an input slot's entry is not shown, so a click there is the input's
        });
        var lists = new Rendered.ListRender(shown, rendered.lists().entries(), rendered.lists().pages());
        return new Rendered(slots, rendered.hidden(), rendered.resolved(), rendered.title(), rendered.coverage(), lists);
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
        paint(inventory, session, previous, next);
    }

    public void paint(Inventory inventory, GuiSession session, Rendered previous, Rendered next) {
        RenderDiff.apply(previous.slots(), next.slots(), (slot, appearance) ->
                inventory.setItem(slot, appearance == null ? null : AppearanceStacks.build(appearance)));
        session.remember(next);
    }

    /** Keeps the page of each list in the session, so {@code gui.page} and the page's text read this render's state. */
    private static void recordPages(GuiSession session, Rendered.ListRender lists) {
        lists.pages().forEach((layer, page) -> {
            Map<String, Object> value = page.toMap();
            if (!value.equals(session.pages().get(layer))) {
                session.pages().put(layer, value);
            }
        });
    }
}
