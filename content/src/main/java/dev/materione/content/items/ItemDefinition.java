package dev.materione.content.items;

import dev.materione.api.NamespacedId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed item definition (runtime-systems §3): identity, appearance,
 * components, behavior hooks and metadata. Unknown fields ride along in
 * {@link #raw()} — round-trip and forward compatibility (§5.6) — but the
 * compiler only reads named fields.
 */
public record ItemDefinition(
        NamespacedId id,
        String material,
        String template,
        String model,             // hand-authored model ref, if any
        String texture,           // generated-model layer0 texture, if any
        String modelParent,       // generated-model parent (item/generated…)
        String displayName,
        List<String> lore,
        Map<String, Object> components,
        List<Map<String, Object>> behavior, // raw ECA rule maps (engine-compiled at apply)
        List<Map<String, Object>> mechanics, // raw mechanic maps ({name: params}), resolved at apply
        Map<String, Object> raw) {

    public ItemDefinition {
        lore = List.copyOf(lore);
        components = Map.copyOf(components);
        behavior = behavior == null ? List.of() : List.copyOf(behavior);
        mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
        raw = Map.copyOf(raw);
    }

    /** True when the definition asks for a generated model (auto-model path). */
    public boolean wantsGeneratedModel() {
        return texture != null && !texture.isBlank();
    }

    /** Merged view: raw fields with named accessors on top (compile reads this). */
    public Map<String, Object> merged() {
        var merged = new LinkedHashMap<String, Object>(raw);
        merged.put("material", material);
        if (displayName != null) {
            merged.put("display_name", displayName);
        }
        return merged;
    }
}
