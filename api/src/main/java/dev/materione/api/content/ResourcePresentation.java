package dev.materione.api.content;

import dev.materione.api.NamespacedId;
import java.util.Map;
import java.util.Objects;

/**
 * Optional, registry-owned catalog fields extracted from a parsed definition.
 * Visual strings are logical references; the authoring layer resolves them
 * against canonical pack assets before exposing a readable path.
 *
 * <p>{@code generatedModel} is the model JSON the type's compiler emits for
 * this resource when it has no hand-authored model (a parent shape plus a
 * texture map, as plain maps and strings) — the editor renders it as the
 * resource's preview, so it must be produced by the same code as the compile
 * output. Null when the type generates no model.
 */
public record ResourcePresentation(
        String name,
        String material,
        String texture,
        String model,
        Reference reference,
        Map<String, Object> generatedModel) {

    public ResourcePresentation {
        generatedModel = generatedModel == null ? null : Map.copyOf(generatedModel);
    }

    /** Source-compatible constructor for presentations without a generated model. */
    public ResourcePresentation(
            String name, String material, String texture, String model, Reference reference) {
        this(name, material, texture, model, reference, null);
    }

    /** Source-compatible constructor for direct visual presentations. */
    public ResourcePresentation(String name, String material, String texture, String model) {
        this(name, material, texture, model, null, null);
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
