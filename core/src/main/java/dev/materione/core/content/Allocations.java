package dev.materione.core.content;

import dev.materione.api.NamespacedId;
import java.util.Optional;

/**
 * The compile-time view over the durable allocation table (master §6.3).
 * Compile hooks ask for stable values here — never derive ids themselves —
 * so assignments survive reloads and restarts byte-for-byte. The runtime
 * never allocates (D7); this interface exists only inside the compile stage.
 */
public interface Allocations {

    /**
     * Returns the durable value for ({@code resource}, {@code kind}),
     * allocating on first ask. Allocation never shifts existing assignments.
     * Kind is a free-form namespace for the value space ({@code block:<host>},
     * {@code glyph-codepoint}, …); values are opaque strings.
     */
    String allocate(NamespacedId resource, String kind);

    /** The live value for ({@code resource}, {@code kind}), if any (tombstones excluded). */
    Optional<String> lookup(NamespacedId resource, String kind);
}
