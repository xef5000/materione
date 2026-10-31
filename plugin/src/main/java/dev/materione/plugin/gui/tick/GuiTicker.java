package dev.materione.plugin.gui.tick;

import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.window.WindowHost;
import java.util.Optional;

/**
 * Drives the two clocks of an open window (logic.md §6.2, §6.3): {@code gui.tick}, which runs rules, and the
 * refresh clock, which re-renders the dynamic layers (conditions, templates, animations). Each is one
 * repeating timer per open session, started only when the GUI has something for it, and each runs at its own
 * period (the greatest common divisor of its rates).
 *
 * <p>Both are chains of one-shot tasks on the viewer's own scheduler, never a task per rule or per layer and
 * never one that outlives the window: each link checks that the session is still open and that the GUI is still
 * live and still needs the clock (re-read every time, so a reload that adds or removes a clock takes effect),
 * and stops the chain when it does not. Closing a GUI therefore needs no cancel call.
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
    private final WindowHost windows;

    public GuiTicker(GuiLookup guis, GuiEvents events, TickScheduler scheduler, WindowHost windows) {
        this.guis = guis;
        this.events = events;
        this.scheduler = scheduler;
        this.windows = windows;
    }

    /**
     * Starts the session's clocks where its GUI needs them and they are not running. Safe to call at any time:
     * on open, and after a reload that may have added the first tick rule or the first dynamic layer.
     */
    public void ensure(Viewer viewer, GuiSession session) {
        if (!session.isOpen()) {
            return;
        }
        Optional<RuntimeGuis.Live> gui = guis.find(session.gui());
        if (gui.isEmpty()) {
            return;
        }
        if (gui.get().logic().ticks() && session.claimTicker()) {
            scheduleTick(viewer, session, gui.get().logic().tickPeriod());
        }
        if (gui.get().clockPeriod() > 0 && session.claimClock()) {
            scheduleClock(viewer, session, gui.get().clockPeriod());
        }
    }

    private void scheduleTick(Viewer viewer, GuiSession session, int period) {
        scheduler.later(viewer, period, () -> wakeTick(viewer, session, period), session::releaseTicker);
    }

    private void wakeTick(Viewer viewer, GuiSession session, int period) {
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
            scheduleTick(viewer, session, gui.get().logic().tickPeriod());
        }
    }

    private void scheduleClock(Viewer viewer, GuiSession session, int period) {
        scheduler.later(viewer, period, () -> wakeClock(viewer, session, period), session::releaseClock);
    }

    private void wakeClock(Viewer viewer, GuiSession session, int period) {
        Optional<RuntimeGuis.Live> gui = session.isOpen() ? guis.find(session.gui()) : Optional.empty();
        if (gui.isEmpty() || gui.get().clockPeriod() == 0 || windows.shape(viewer, session).isEmpty()) {
            session.releaseClock();
            return;
        }
        session.advanceWindowTicks(period);
        try {
            windows.advance(viewer, session, gui.get());
        } finally {
            // the next link uses the GUI as it is now, so a reload that changes the clock is followed
            int next = gui.get().clockPeriod();
            if (next > 0) {
                scheduleClock(viewer, session, next);
            } else {
                session.releaseClock();
            }
        }
    }
}
