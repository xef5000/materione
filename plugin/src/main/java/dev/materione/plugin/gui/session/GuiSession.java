package dev.materione.plugin.gui.session;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ContextFacet;
import dev.materione.plugin.gui.render.Rendered;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One GUI open for one player (logic.md §3.1, §6): which GUI, the arguments it was opened with, the
 * session variables, the per-list page state and the identity that owns the runs its clicks start.
 * It lives exactly as long as the window is open. A live reload of the GUI's content keeps the same
 * session (the window may be rewritten or re-opened, the session is the player's conversation with
 * the menu), while {@code gui.back} restores a previous one from a {@link Snapshot}.
 *
 * <p>The session is the cancellation key of every run it starts ({@code BehaviorEvent.withCancelKey}),
 * compared by identity. Runs belong to the player, not to the window (logic.md §5.2), so closing does
 * not cancel them; a flow that wants the stricter behaviour says {@code stop: {cancel_session_runs:
 * true}}.
 *
 * <p>Threading: a session is created, read and mutated on its player's thread (the region thread on
 * Folia). The maps are concurrent so a read from the quit or refresh path can never corrupt them, but
 * nothing here orders two writers: there is only ever one.
 */
public final class GuiSession {

    /** The facet that carries the session into the runs it starts. Declared here, next to the type it holds. */
    public static final ContextFacet<GuiSession> FACET = ContextFacet.of("materione:gui_session", GuiSession.class);

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long serial = SEQUENCE.incrementAndGet();
    private final UUID player;
    private final String playerName;
    private final NamespacedId gui;
    private final Map<String, String> args;
    private final Map<String, Object> vars = new ConcurrentHashMap<>();
    private final Map<String, Object> pages = new ConcurrentHashMap<>();
    private final AtomicReference<CloseReason> ended = new AtomicReference<>();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private final AtomicBoolean clocking = new AtomicBoolean();
    private volatile long elapsedTicks;
    private volatile long windowTicks;
    private volatile Rendered rendered = Rendered.EMPTY;
    private volatile Object window;

    /**
     * A session about to open.
     *
     * @param args the arguments the GUI was opened with, by name; copied and read-only afterwards
     */
    public GuiSession(UUID player, String playerName, NamespacedId gui, Map<String, String> args) {
        this.player = Objects.requireNonNull(player, "player");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.gui = Objects.requireNonNull(gui, "gui");
        this.args = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(args, "args")));
    }

    /** A process-unique number, for logs. */
    public long serial() {
        return serial;
    }

    public UUID player() {
        return player;
    }

    public String playerName() {
        return playerName;
    }

    /** The GUI this session shows. */
    public NamespacedId gui() {
        return gui;
    }

    /** The arguments the GUI was opened with (read by rules as {@code gui.args.<name>}). */
    public Map<String, String> args() {
        return args;
    }

    /**
     * The session variables (read by rules as {@code gui.session.<name>}). The map is live: the
     * variables epic writes through it ({@code set_var} with the session scope), and {@code gui.back}
     * restores it.
     */
    public Map<String, Object> vars() {
        return vars;
    }

    /** Per-list page state ({@code gui.page.<list>}); the seam the list component fills. */
    public Map<String, Object> pages() {
        return pages;
    }

    /** True until the session ends. */
    public boolean isOpen() {
        return ended.get() == null;
    }

    /** Why the session ended, or null while it is open. */
    public CloseReason endedBecause() {
        return ended.get();
    }

    /**
     * Ends the session. Only the first call counts, so a close that races a replace fires its trigger
     * once.
     *
     * @return true when this call ended it
     */
    public boolean end(CloseReason reason) {
        return ended.compareAndSet(null, Objects.requireNonNull(reason, "reason"));
    }

    /**
     * The window currently showing this session, as the host recorded it ({@code Object}: the session
     * holds no server type). A window that closes while it is no longer this one was replaced by the
     * host (a re-open after a title change), not closed by the player.
     */
    public Object window() {
        return window;
    }

    /** Records the window that is about to show this session. */
    public void attach(Object window) {
        this.window = window;
    }

    /** Claims the right to run this session's ticker; false when one already runs. */
    public boolean claimTicker() {
        return ticking.compareAndSet(false, true);
    }

    /** Releases the ticker claim (the ticker stopped, for instance because no rule ticks any more). */
    public void releaseTicker() {
        ticking.set(false);
    }

    /** Claims the right to run this session's refresh clock; false when one already runs. */
    public boolean claimClock() {
        return clocking.compareAndSet(false, true);
    }

    /** Releases the refresh clock claim (the clock stopped: the window closed, or the GUI has no clock). */
    public void releaseClock() {
        clocking.set(false);
    }

    /** The last render of the window this session shows; {@link Rendered#EMPTY} before the first one. */
    public Rendered rendered() {
        return rendered;
    }

    /** Records a render of the window (the paint path). */
    public void remember(Rendered next) {
        rendered = next;
    }

    /** A new window: nothing is shown yet, and its clock starts at zero (so an animation starts at its first frame). */
    public void forgetWindow() {
        rendered = Rendered.EMPTY;
        windowTicks = 0;
    }

    /** Ticks the window has been open on the refresh clock, which chooses an animation's frame. */
    public long windowTicks() {
        return windowTicks;
    }

    /** Advances the window's clock by {@code ticks}, the refresh clock's period. */
    public long advanceWindowTicks(long ticks) {
        windowTicks += ticks;
        return windowTicks;
    }

    /** Ticks the window has been open, as counted by the ticker. */
    public long elapsedTicks() {
        return elapsedTicks;
    }

    /** Advances the ticker's clock. */
    public long advanceTicks(long ticks) {
        elapsedTicks += ticks;
        return elapsedTicks;
    }

    /** What {@code gui.back} needs to bring this session's GUI back as it was. */
    public Snapshot snapshot() {
        return new Snapshot(gui, args, Map.copyOf(vars), Map.copyOf(pages));
    }

    /** Puts a snapshot's variables and page state into this (fresh) session. */
    public void restore(Snapshot snapshot) {
        vars.putAll(snapshot.vars());
        pages.putAll(snapshot.pages());
    }

    @Override
    public String toString() {
        return "session#" + serial + "[" + gui + " for " + playerName + (isOpen() ? "" : ", ended") + "]";
    }

    /**
     * A session at the moment its window was left for another GUI: the arguments it opened with and the
     * variables and page state it had. Immutable.
     */
    public record Snapshot(
            NamespacedId gui, Map<String, String> args, Map<String, Object> vars, Map<String, Object> pages) {

        public Snapshot {
            args = Map.copyOf(args);
            vars = Map.copyOf(vars);
            pages = Map.copyOf(pages);
        }
    }
}
