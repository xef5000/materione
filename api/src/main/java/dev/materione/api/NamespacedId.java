package dev.materione.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Namespaced identifier for content and registry entries:
 * {@code namespace:value} (e.g. {@code mystuff:ruby_sword}).
 *
 * <p>One namespace per pack; {@code minecraft} is reserved (master
 * architecture §5.1) — the id type itself permits it so vanilla references
 * can be expressed, but registries reject it at registration time (see
 * {@link #isReservedNamespace()}).
 *
 * <p>Value semantics: immutable, equality by namespace+value.
 */
public record NamespacedId(String namespace, String value) {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9_.-]+");

    /** The namespace Materione never owns. */
    public static final String RESERVED_NAMESPACE = "minecraft";

    public NamespacedId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(value, "value");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Invalid namespace '" + namespace + "': must match [a-z0-9_-]+");
        }
        if (!VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid id '" + value + "': must match [a-z0-9_.-]+");
        }
    }

    /**
     * Parses {@code namespace:value}. Exactly one colon; no whitespace.
     *
     * @throws IllegalArgumentException on malformed input
     */
    public static NamespacedId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        int colon = raw.indexOf(':');
        if (colon < 0 || colon != raw.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "Invalid namespaced id '" + raw + "': expected exactly one ':'");
        }
        return new NamespacedId(raw.substring(0, colon), raw.substring(colon + 1));
    }

    /** Canonical factory (alias of the constructor). */
    public static NamespacedId of(String namespace, String value) {
        return new NamespacedId(namespace, value);
    }

    /** True if this id claims the reserved {@code minecraft} namespace. */
    public boolean isReservedNamespace() {
        return RESERVED_NAMESPACE.equals(namespace);
    }

    @Override
    public String toString() {
        return namespace + ":" + value;
    }
}
