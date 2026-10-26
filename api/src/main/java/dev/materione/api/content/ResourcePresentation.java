package dev.materione.api.content;

import dev.materione.api.NamespacedId;
import java.util.Objects;

/**
 * Optional, registry-owned catalog fields extracted from a parsed definition.
 * Visual strings are logical references; the authoring layer resolves them
 * against canonical pack assets before exposing a readable path.
 */
public record ResourcePresentation(
        String name,
        String material,
        String texture,
        String model,
        Reference reference) {

    /** Source-compatible constructor for direct visual presentations. */
    public ResourcePresentation(String name, String material, String texture, String model) {
        this(name, material, texture, model, null);
    }

    /**
     * A typed link (using the content-directory name) to another canonical
     * resource whose presentation supplies this resource's visual when it has
     * no direct texture or model.
     */
    public record Reference(String type, NamespacedId id) {
        public Reference {
            if (type == null || !type.matches("[a-z0-9_-]+")) {
                throw new IllegalArgumentException("presentation reference type is invalid");
            }
            Objects.requireNonNull(id, "presentation reference id");
        }
    }
}
