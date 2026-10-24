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

    /** The human's answer: approved at all, and whether to trust this browser's key. */
    record Decision(boolean approved, boolean trustWorkstation) {

        public static final Decision DENIED = new Decision(false, false);
        public static final Decision APPROVED = new Decision(true, false);
        public static final Decision APPROVED_AND_TRUST = new Decision(true, true);
    }

    /**
     * Asks a human to trust a connecting editor. Implementations should
     * complete the future with a {@link Decision} and never block the caller
     * thread.
     *
     * @param noncePrefix first bytes of the hello nonce, hex-encoded — the
     *                    disambiguator when several editors connect at once
     * @param browser     free-text browser label the editor reported
     */
    CompletableFuture<Decision> prompt(String noncePrefix, String browser);
}
