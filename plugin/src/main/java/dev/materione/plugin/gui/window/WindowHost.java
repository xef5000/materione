package dev.materione.plugin.gui.window;

import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.function.Consumer;

/**
 * The server-facing half of opening and closing a GUI: creates the window, shows it, hides it,
 * rewrites its contents. The opener, the lifecycle and the refresh work on sessions and call this for
 * the one thing a session cannot do itself, so their logic is tested against a fake host and the real
 * one ({@link BukkitWindowHost}) is the only code here that touches a Bukkit inventory.
 *
 * <p>All methods are called on the viewer's thread.
 */
public interface WindowHost {

    /**
     * Shows {@code gui} to the viewer for {@code session}, replacing whatever window they have.
     * Either {@code opened} runs once the window is up (possibly a tick later, when the viewer is
     * mid-click), or {@code failed} runs with the reason; exactly one of them.
     */
    void open(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, Runnable opened, Consumer<String> failed);

    /**
     * Opens a new window for the same session after the GUI's title or size changed (the cursor item
     * travels with the viewer). Same completion contract as {@link #open}.
     */
    void reopen(Viewer viewer, GuiSession session, RuntimeGuis.Live gui, Runnable opened, Consumer<String> failed);

    /** Closes the viewer's window (a tick later when they are mid-click). */
    void close(Viewer viewer);

    /**
     * Rewrites the contents of the viewer's window in place, when it is this session's window. Every layer
     * is read again: this is what {@code gui.refresh} and a reload need.
     */
    void rewrite(Viewer viewer, GuiSession session, RuntimeGuis.Live gui);

    /**
     * Repaints the dynamic layers of the viewer's window, when it is this session's window: the refresh
     * clock's tick. Layers that do not change keep what they show, and the window's clock advances first.
     */
    void advance(Viewer viewer, GuiSession session, RuntimeGuis.Live gui);

    /**
     * The title and size of the window the viewer has open for this session, or empty when their
     * open window is not this session's. A window's title and size are fixed when it is created, so
     * the refresh compares them with the GUI's now to choose between a rewrite and a re-open.
     */
    java.util.Optional<Shape> shape(Viewer viewer, GuiSession session);

    /** A window's creation-time title (MiniMessage) and rows. */
    record Shape(String title, int rows) {}
}
