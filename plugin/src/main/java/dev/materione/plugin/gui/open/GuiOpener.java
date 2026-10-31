package dev.materione.plugin.gui.open;

import dev.materione.plugin.gui.GuiLookup;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.event.GuiEvents;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import dev.materione.plugin.gui.session.GuiSessions;
import dev.materione.plugin.gui.tick.GuiTicker;
import dev.materione.plugin.gui.window.WindowHost;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The one way a GUI opens (logic.md §6.1). The command, the runtime API (and with it the editor's
 * preview), the {@code gui.open} action and {@code gui.back} all build an {@link OpenRequest} and call
 * {@link #open}; nothing else creates a session or a window, so every way in has the same rules.
 *
 * <p>The stages, in order:
 *
 * <ol>
 *   <li>Resolve the GUI from the live view; unknown or quarantined means {@link OpenResult.Outcome#NOT_LIVE}.
 *   <li>Ask the {@link OpenGate} (permission, typed arguments, requirement). It owns stages 2 to 4 of the
 *       design and answers with the arguments the session opens with, or a refusal.
 *   <li>Fire the cancellable {@code gui.opening}. A rule that cancels ends the open here.
 *   <li>Create the session. The window the player already has is replaced: its session ends with
 *       {@code replaced}, {@code gui.close} fires for it, and (unless this open is {@code gui.back}) it goes
 *       on the back stack.
 *   <li>Render and open the window through the {@link WindowHost}.
 *   <li>Fire {@code gui.open} once the window is up, and start the session's ticker.
 * </ol>
 *
 * <p>Call it on the viewer's own thread. It never opens a window in the middle of the viewer's click:
 * the host defers that to the next tick, and the session switches at once.
 */
public final class GuiOpener {

    private final GuiLookup guis;
    private final GuiSessions sessions;
    private final GuiEvents events;
    private final WindowHost windows;
    private final GuiTicker ticker;
    private final Logger log;
    private volatile OpenGate gate = OpenGate.ALLOW;

    public GuiOpener(GuiLookup guis, GuiSessions sessions, GuiEvents events, WindowHost windows, GuiTicker ticker,
            Logger log) {
        this.log = Objects.requireNonNull(log, "log");
        this.guis = Objects.requireNonNull(guis, "guis");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.events = Objects.requireNonNull(events, "events");
        this.windows = Objects.requireNonNull(windows, "windows");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** Installs the open specification's gate (permission, typed arguments, requirement and deny). */
    public void gate(OpenGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** Runs the stages for one request. */
    public OpenResult open(OpenRequest request) {
        Viewer viewer = request.viewer();
        Optional<RuntimeGuis.Live> found = guis.find(request.gui());
        if (found.isEmpty()) {
            return OpenResult.refused(OpenResult.Outcome.NOT_LIVE,
                    "GUI '" + request.gui() + "' is not live: it is unknown, or its last compile quarantined it");
        }
        RuntimeGuis.Live gui = found.get();
        OpenGate.Admission admission = gate.admit(viewer, gui, request.args());
        if (!admission.allowed()) {
            return OpenResult.refused(OpenResult.Outcome.DENIED, admission.reason());
        }

        var session = new GuiSession(viewer.id(), viewer.name(), gui.id(), admission.args());
        if (request.restore() != null) {
            session.restore(request.restore());
        }
        if (events.opening(viewer, session, gui)) {
            return OpenResult.refused(OpenResult.Outcome.CANCELLED, "a gui.opening rule cancelled the open");
        }

        sessions.begin(session).ifPresent(previous -> replace(viewer, previous, request.cause()));
        if (sessions.current(viewer.id()).filter(current -> current == session).isEmpty()) {
            // a gui.close rule of the replaced GUI opened something else: that open won, this one is stale
            session.end(CloseReason.REMOVED);
            return OpenResult.refused(OpenResult.Outcome.CANCELLED, "another GUI opened while this one was opening");
        }

        Consumer<String> failed = reason -> {
            sessions.end(session, CloseReason.REMOVED);
            log.warning("MATERIONE-GUI-FAILED reason=window gui=" + gui.id() + " player=" + viewer.name()
                    + " detail=" + reason);
        };
        windows.open(viewer, session, gui, () -> {
            events.opened(viewer, session, gui);
            ticker.ensure(viewer, session);
        }, failed);
        return OpenResult.opened(session);
    }

    /** The viewer's previous GUI is replaced: remember it for {@code gui.back}, end it, fire its close. */
    private void replace(Viewer viewer, GuiSession previous, OpenRequest.Cause cause) {
        if (cause != OpenRequest.Cause.BACK) {
            sessions.history(viewer.id()).push(previous.snapshot());
        }
        if (sessions.end(previous, CloseReason.REPLACED)) {
            guis.find(previous.gui()).ifPresent(gui -> events.closed(viewer, previous, gui, CloseReason.REPLACED));
            windows.releaseInputs(viewer, previous); // the player's input items leave with the window they were in
        }
    }
}
