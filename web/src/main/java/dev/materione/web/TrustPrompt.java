package dev.materione.web;

import java.util.concurrent.CompletableFuture;

/**
 * The in-game trust decision point (web.md §4.1 step 6): when a browser says
 * hello, somebody holding the server console or an admin permission must
 * approve it, recognizing the shown nonce prefix. Implementations live on
 * the host side (the plugin shows clickable chat); web/ only awaits the
 * answer — which keeps every Bukkit interaction out of this module.
 */
public interface TrustPrompt {

    /**
     * Asks a human to trust a connecting editor. Implementations should
     * complete the future with {@code true} (approved) or {@code false}
     * (denied/expired) and never block the caller thread.
     *
     * @param noncePrefix first bytes of the hello nonce, hex-encoded — the
     *                    disambiguator when several editors connect at once
     * @param browser     free-text browser label the editor reported
     */
    CompletableFuture<Boolean> prompt(String noncePrefix, String browser);
}
