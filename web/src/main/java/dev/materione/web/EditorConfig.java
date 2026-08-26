package dev.materione.web;

import java.util.Objects;

/**
 * Immutable listener configuration resolved from settings ({@code editor_*}
 * keys) plus whatever port-fallback negotiation the caller performed. The
 * listener never reads settings directly — the host decides (web/ stays
 * embeddable outside a Paper server, mirroring {@code behavior}'s R7 shape).
 *
 * @param host bind address; loopback by default, anything wider earns an
 *             operator-visible warning diagnostic (MTR-0036)
 */
public record EditorConfig(String host, int port) {

    public EditorConfig {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    /** True when the bind address can only be reached from this machine. */
    public boolean loopbackOnly() {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equals(host);
    }
}
