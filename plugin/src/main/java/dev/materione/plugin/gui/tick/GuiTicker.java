package dev.materione.plugin.gui.tick;

import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Optional;

/**
 * Drives {@code gui.tick} (logic.md §6.2): one repeating timer per open session, and only while the
 * GUI has a tick rule. The timer's period is the greatest common divisor of the rules' {@code every:}
 * values, so a GUI with rules at 20 and 100 wakes every 20 ticks and one with a rule at 1 wakes every
 * tick; each wake counts the elapsed ticks and runs the rules whose {@code every} divides them.
 *
 * <p>The timer is a chain of one-shot tasks on the viewer's own scheduler, never a task per rule and
 * never one that outlives the window: each link checks that the session is still open and that the
 * GUI is still live and still ticks (re-read every time, so a reload that adds or removes tick rules
 * takes effect), and stops the chain when it does not. Closing a GUI therefore needs no cancel call.
 */
public final class GuiTicker {

    /** Runs a task on the viewer's thread after a delay; {@code retired} runs instead if the viewer is gone. */
    @FunctionalInterface
    public interface TickScheduler {
        void later(Viewer viewer, long ticks, Runnable task, Runnable retired);
    }

    private final GuiLookup guis;
    private final GuiEvents events;
    private final TickScheduler scheduler;

    public GuiTicker(GuiLookup guis, GuiEvents events, TickScheduler scheduler) {
        this.guis = guis;
        this.events = events;
        this.scheduler = scheduler;
    }

    /**
     * Starts the session's timer if its GUI has tick rules and none is running. Safe to call at any
     * time: on open, and after a reload that may have added the first tick rule.
     */
    public void ensure(Viewer viewer, GuiSession session) {
        if (!session.isOpen()) {
            return;
        }
        Optional<RuntimeGuis.Live> gui = guis.find(session.gui());
        if (gui.isEmpty() || !gui.get().logic().ticks() || !session.claimTicker()) {
            return;
        }
        schedule(viewer, session, gui.get().logic().tickPeriod());
    }

    private void schedule(Viewer viewer, GuiSession session, int period) {
        scheduler.later(viewer, period, () -> wake(viewer, session, period), session::releaseTicker);
    }

    private void wake(Viewer viewer, GuiSession session, int period) {
        Optional<RuntimeGuis.Live> gui = session.isOpen() ? guis.find(session.gui()) : Optional.empty();
        if (gui.isEmpty() || !gui.get().logic().ticks()) {
            session.releaseTicker();
            return;
        }
        long elapsed = session.advanceTicks(period);
        try {
            events.tick(viewer, session, gui.get(), elapsed);
        } finally {
            // the chain continues even if a rule threw; the next link re-checks everything
            schedule(viewer, session, gui.get().logic().tickPeriod());
        }
    }
}
