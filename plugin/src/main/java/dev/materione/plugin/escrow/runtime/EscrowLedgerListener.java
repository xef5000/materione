package dev.materione.plugin.escrow.runtime;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The two platform events the ledger needs (gui-input-durability.md §5.7), and nothing else.
 *
 * <p><b>Pre-login</b> ({@code AsyncPlayerPreLoginEvent}, F9): the event fires off the main thread before the player
 * exists, so it may wait. The ledger of a player whose login is allowed is read into memory, queued behind any
 * in-flight append for the same UUID, so the join handler's recovery needs no IO. It never refuses the login: a
 * problem is a {@link dev.materione.plugin.escrow.io.LedgerState.Unrecoverable} state that the store reports
 * ({@code MTR-0101}); a preload that does not finish within {@link EscrowLedgerStore#PRELOAD_TIMEOUT} leaves the
 * state absent ({@code preloaded(uuid)} is empty, so the recovery gate stays closed) and reports {@code MTR-0101}
 * here. This is the only class allowed to call {@link EscrowLedgerStore#awaitPreload} (a ban test).
 *
 * <p><b>Quit</b>: drops the in-memory image after everything queued for the player ran. It does no IO itself.
 */
public final class EscrowLedgerListener implements Listener {

    private final EscrowLedgerStore store;
    private final Duration timeout;
    private final Consumer<Diagnostic> diagnostics;

    /** A listener over {@code store}; {@code timeout} is {@link EscrowLedgerStore#PRELOAD_TIMEOUT} in production. */
    public EscrowLedgerListener(EscrowLedgerStore store, Duration timeout, Consumer<Diagnostic> diagnostics) {
        this.store = store;
        this.timeout = timeout;
        this.diagnostics = diagnostics;
    }

    /** Reads the joining player's ledger into memory (async thread). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (admitted(event.getLoginResult())) {
            preload(event.getUniqueId());
        }
    }

    /** Only a login that is still allowed at MONITOR will join: a refused one reads nothing. */
    static boolean admitted(AsyncPlayerPreLoginEvent.Result result) {
        return result == AsyncPlayerPreLoginEvent.Result.ALLOWED;
    }

    /** The pre-login work, separated from the event type so a test can drive it. */
    public void preload(UUID player) {
        if (store.awaitPreload(player, timeout).isEmpty()) {
            diagnostics.accept(new Diagnostic(DiagnosticCodes.ESCROW_STATE_UNREADABLE, Severity.WARN,
                    "escrow ledger of " + player + " did not finish loading within " + timeout.toSeconds()
                            + " s; its strict input transfers stay refused until it does",
                    null, null, "the read continues in the background; check the disk the server's storage folder is on"));
        }
    }

    /** Drops the leaving player's in-memory image. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        store.release(event.getPlayer().getUniqueId());
    }
}
