package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.GuiMenu;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.open.GuiLifecycle;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The adapter for the two events that end a GUI session without a flow asking (logic.md §6.2): a
 * window closing and a player leaving. It converts each to a {@link GuiLifecycle} call and holds no
 * policy of its own.
 *
 * <p>A window that closes while it is no longer its session's window was replaced by the host (a
 * re-open after a title change, or another GUI opening over it); that is not the player closing it, and
 * the session is left alone.
 */
public final class GuiWindowListener implements Listener {

    private final GuiLifecycle lifecycle;
    private final GuiJournal journal;

    public GuiWindowListener(GuiLifecycle lifecycle, GuiJournal journal) {
        this.lifecycle = lifecycle;
        this.journal = journal;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiMenu menu) || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        // whatever window closes, the player's items in its input slots are theirs: a window that was
        // replaced by a reopen has already handed them over, so this finds nothing and changes nothing
        InputReturns.release(player, menu);
        journal.mirror(player); // the journal follows the player's current window, which may be another one
        GuiSession session = menu.session();
        if (session.window() != menu) {
            return;
        }
        CloseReason reason = event.getReason() == InventoryCloseEvent.Reason.DISCONNECT
                ? CloseReason.QUIT
                : CloseReason.PLAYER;
        lifecycle.windowClosed(Viewer.of(player), session, reason);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lifecycle.playerQuit(Viewer.of(event.getPlayer()));
    }

    /** A join after a crash: whatever the journal still holds was in an open window's input slots, and goes back. */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        journal.restore(event.getPlayer());
    }
}
