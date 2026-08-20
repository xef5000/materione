package dev.materione.api.registry;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies one registry (e.g. {@code content-types}, {@code mechanics},
 * {@code actions}, {@code conditions}, {@code hosting-backends}). Registry
 * keys are plain slugs — entries inside a registry carry the namespaced ids.
 */
public record RegistryKey(String id) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]*");

    public RegistryKey {
        Objects.requireNonNull(id, "id");
        if (!FORMAT.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Invalid registry key '" + id + "': must match [a-z][a-z0-9-]*");
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
