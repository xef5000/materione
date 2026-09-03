package dev.materione.core.delivery;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A pack hosting backend (master §8.4): publishes a compiled pack and
 * returns its versioned, cache-busting URL. Implementations: self-host
 * HTTP (M1), S3/R2 and external URLs (M7). Delivery failure never rolls
 * back apply — the server runs the new content; players keep the old pack
 * until delivery succeeds (plugin.md §4.6).
 */
public interface PackDeliveryBackend extends AutoCloseable {

    /**
     * Publishes the pack and returns the URL players fetch. The URL embeds
     * the pack version (hash) so a new pack is never served from a cached
     * URL.
     */
    String publish(Path packZip, String sha256) throws IOException;

    /** Protects a hosted version from bounded-retention eviction. */
    default void pin(String url) {}

    /** Releases a previously protected hosted version. */
    default void unpin(String url) {}

    @Override
    default void close() {}
}
