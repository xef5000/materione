package dev.materione.plugin.escrow.runtime;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * The platform events the escrow runtime reacts to, and nothing else (gui-input-durability.md §5.5, §5.7): an adapter
 * that hands each event to {@link EscrowRuntime}.
 *
 * <ul>
 *   <li><b>Join</b> at {@link EventPriority#LOWEST}: the gate closes and recovery runs before any other plugin's join
 *       handler can let the player interact.
 *   <li><b>Death</b> at {@link EventPriority#MONITOR}, deliberately: the release must follow the <em>final</em>
 *       {@code getKeepInventory()} (another plugin may flip it), and the stacks have to be in the event before the
 *       server fixes the drop list right after it (F10). Nothing else is changed there.
 *   <li><b>Respawn</b>: the live account is written into the respawned player's data again.
 *   <li><b>Quit</b> at {@code MONITOR}: the server has already closed the window and so released it (F4).
 * </ul>
 */
public final class EscrowPlayerListener implements Listener {

    private final EscrowRuntime runtime;

    public EscrowPlayerListener(EscrowRuntime runtime) {
        this.runtime = runtime;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        runtime.join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        runtime.death(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        runtime.respawned(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        runtime.quit(event.getPlayer());
    }
}
