package dev.materione.web.relay;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Decides whether a cloud session's sealed blob carries a direct-mode hint
 * (web.md §4.4). The hint is only an <em>invitation to probe</em>: the cloud
 * editor never trusts it, and switches to the local listener solely after a
 * browser-side, session-bound cryptographic proof (probe.ts / ProbeMessage).
 * This policy therefore only has to avoid pointless probes — notably the
 * Chrome "local network" permission prompt a probe can raise — not to be
 * correct about reachability, which server-side facts can never establish
 * (NAT, containers and hosting-panel port maps all lie).
 *
 * <p>Modes ({@code editor_direct_hint}):
 * <ul>
 *   <li>{@code auto} — hint only when the evidence says the admin's
 *       browser is on this machine (the command sender connected from a
 *       loopback address), or the owner pinned an HTTPS front
 *       ({@code editor_public_url});</li>
 *   <li>{@code always} — hint whenever the listener runs (dev/test, or an
 *       owner who knows the browser is co-located);</li>
 *   <li>{@code off} (default) — never: the direct listener is not advertised;
 *       cloud links never invite a local probe.</li>
 * </ul>
 */
public final class DirectHint {

    public enum Mode {
        AUTO, ALWAYS, OFF;

        /** Missing or unrecognized is {@code OFF} (the default and safest), never {@code ALWAYS}. */
        public static Mode parse(String text) {
            if (text == null) {
                return OFF;
            }
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "off", "false", "never", "no" -> OFF;
                case "always", "true", "yes" -> ALWAYS;
                case "auto" -> AUTO;
                default -> OFF;
            };
        }
    }

    private DirectHint() {}

    /**
     * @param mode            the {@code editor_direct_hint} setting
     * @param listenerRunning whether the embedded listener is bound
     * @param boundHost       the host it bound ({@code 127.0.0.1}, {@code 0.0.0.0}, a LAN address)
     * @param boundPort       the port it bound
     * @param publicUrl       {@code editor_public_url}: an {@code https://} origin that fronts the
     *                        listener (TLS reverse proxy); blank when unset
     * @param publicHost      {@code editor_public_host}: advertised host for plain-HTTP setups
     * @param relayBaseUrl    the editor page's origin; a plain-HTTP page (self-hosted dev relay) may
     *                        probe plain-HTTP targets, an HTTPS page may not (mixed content)
     * @param senderAddress   the remote address of the player who ran the command, or null (console)
     * @return the hint URL, or null for "no hint"
     */
    public static String resolve(
            Mode mode,
            boolean listenerRunning,
            String boundHost,
            int boundPort,
            String publicUrl,
            String publicHost,
            String relayBaseUrl,
            InetAddress senderAddress) {
        if (mode == Mode.OFF || !listenerRunning || boundPort <= 0) {
            return null;
        }
        // An HTTPS front the owner pinned: usable from the HTTPS cloud page
        // with no mixed-content problem. Still only a hint — the proof decides.
        String pinned = httpsOrigin(publicUrl);
        if (pinned != null) {
            return pinned;
        }
        boolean cloudPageIsPlainHttp = relayBaseUrl != null && relayBaseUrl.toLowerCase(Locale.ROOT).startsWith("http://");
        if (cloudPageIsPlainHttp && publicHost != null && !publicHost.isBlank()) {
            return "http://" + publicHost.trim() + ":" + boundPort;
        }
        // Loopback only helps when the browser shares this machine, and only
        // when the listener really answers on loopback.
        boolean loopbackReachable = isLoopbackBind(boundHost);
        boolean browserLooksLocal = mode == Mode.ALWAYS
                || (senderAddress != null && senderAddress.isLoopbackAddress());
        if (loopbackReachable && browserLooksLocal) {
            return "http://127.0.0.1:" + boundPort;
        }
        return null;
    }

    /** True when {@code 127.0.0.1} reaches a listener bound to {@code host}. */
    static boolean isLoopbackBind(String host) {
        if (host == null) {
            return false;
        }
        return switch (host.trim().toLowerCase(Locale.ROOT)) {
            case "127.0.0.1", "localhost", "::1", "[::1]", "0.0.0.0", "::", "[::]" -> true;
            default -> host.trim().startsWith("127.");
        };
    }

    /** A clean {@code https://host[:port]} origin, or null when unset/not HTTPS/malformed. */
    static String httpsOrigin(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return "https://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
