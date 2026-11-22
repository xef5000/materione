package dev.materione.plugin.gui.session;

import dev.materione.api.NamespacedId;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.render.Rendered;
import dev.materione.plugin.view.ViewerPayload;
import dev.materione.plugin.view.ViewerRecord;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The container GUI's viewer-local state (views.md §3.3, §3.8): what only this window's screen needs. It is the
 * payload of the {@link ViewerRecord} a GUI viewer has, and it is everything the old {@code GuiSession} held that was
 * not the binding or the session values: the per-list page state, the last render (the diff base and the layers the
 * click router follows, logic.md §24 decision 14), the definition installed in the window, the clocks and the
 * ticker claims.
 *
 * <p>Nothing here is read by a rule outside the viewer's own runs, and nothing here is shared between viewers; a
 * value another player's view could need is state in the variable store, not here (views.md §3.3). Mutated only on
 * the viewer's owner thread; each mutator reports itself through {@link ViewerRecord#mutating(String)}.
 */
public final class GuiViewerState implements ViewerPayload {

    /** The kind's name: the content type of a GUI. */
    public static final String KIND = "gui";

    private static final String PAGES = "pages";

    private final Map<String, Object> pages = new ConcurrentHashMap<>();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final AtomicBoolean clocking = new AtomicBoolean();
    private volatile long elapsedTicks;
    private volatile long windowTicks;
    private volatile Rendered rendered = Rendered.EMPTY;
    private volatile RuntimeGuis.Live displayed;
    private volatile ViewerRecord owner;

    /**
     * A new viewer record for a GUI about to open: the binding, with a fresh GUI payload.
     *
     * @param args the arguments the GUI was opened with, by name; copied and read-only afterwards
     */
    public static ViewerRecord record(UUID player, String playerName, NamespacedId gui, Map<String, String> args) {
        return new ViewerRecord(player, playerName, KIND, gui, args, new GuiViewerState());
    }

    /** The GUI state of a GUI's record. A record of another kind has none: asking is a programming error. */
    public static GuiViewerState of(ViewerRecord record) {
        if (record.payload() instanceof GuiViewerState state) {
            return state;
        }
        throw new IllegalStateException(record + " is not a GUI viewer");
    }

    @Override
    public void boundTo(ViewerRecord record) {
        this.owner = record;
    }

    private void mutating(String what) {
        ViewerRecord record = owner;
        if (record != null) {
            record.mutating(what);
        }
    }

    /** Per-list page state ({@code view.page.<list>}); the seam the list component fills. */
    public Map<String, Object> pages() {
        return pages;
    }

    /** Claims the right to run this viewer's ticker; false when one already runs. */
    public boolean claimTicker() {
        return ticking.compareAndSet(false, true);
    }

    /** Releases the ticker claim (the ticker stopped, for instance because no rule ticks any more). */
    public void releaseTicker() {
        ticking.set(false);
    }

    /** Claims the right to run this viewer's refresh clock; false when one already runs. */
    public boolean claimClock() {
        return clocking.compareAndSet(false, true);
    }

    /** Releases the refresh clock claim (the clock stopped: the window closed, or the GUI has no clock). */
    public void releaseClock() {
        clocking.set(false);
    }

    /** The last render of the window this record shows; {@link Rendered#EMPTY} before the first one. */
    public Rendered rendered() {
        return rendered;
    }

    /** Records a render of the window (the paint path). */
    public void remember(Rendered next) {
        mutating("last render");
        rendered = next;
    }

    /** The immutable definition whose presentation is currently installed in this window. */
    public Optional<RuntimeGuis.Live> displayed() {
        return Optional.ofNullable(displayed);
    }

    /** Records the definition after its presentation and input ownership have been installed. */
    public void displayed(RuntimeGuis.Live gui) {
        mutating("displayed definition");
        displayed = gui;
    }

    /** A new window: nothing is shown yet, and its clock starts at zero (so an animation starts at its first frame). */
    public void forgetWindow() {
        mutating("window");
        rendered = Rendered.EMPTY;
        windowTicks = 0;
    }

    /** Ticks the window has been open on the refresh clock, which chooses an animation's frame. */
    public long windowTicks() {
        return windowTicks;
    }

    /** Advances the window's clock by {@code ticks}, the refresh clock's period. */
    public long advanceWindowTicks(long ticks) {
        mutating("window clock");
        windowTicks += ticks;
        return windowTicks;
    }

    /** Ticks the window has been open, as counted by the ticker. */
    public long elapsedTicks() {
        return elapsedTicks;
    }

    /** Advances the ticker's clock. */
    public long advanceTicks(long ticks) {
        mutating("ticker clock");
        elapsedTicks += ticks;
        return elapsedTicks;
    }

    /** What {@code view.back} keeps of a GUI beyond the binding: the page of every list. */
    @Override
    public Map<String, Object> snapshot() {
        return Map.of(PAGES, Map.copyOf(pages));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(Map<String, Object> state) {
        if (state.get(PAGES) instanceof Map<?, ?> saved) {
            mutating("restore");
            pages.putAll((Map<String, Object>) saved);
        }
    }
}
