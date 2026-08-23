package dev.materione.content.blocks;

import dev.materione.api.NamespacedId;
import dev.materione.core.blocks.BlockStateSpaces;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed block definition (runtime-systems §4): a hijacked host state
 * rendered through a custom model, plus world-facing policy (drops,
 * explosions, pistons). Unknown fields ride along in {@link #raw()} for
 * round-trip compatibility (§5.6); the compiler and runtime read named
 * fields.
 */
public record BlockDefinition(
        NamespacedId id,
        BlockStateSpaces.Technique technique,
        String host,              // explicit technical host; derived per technique otherwise
        String model,             // hand-authored model ref, if any
        String texture,           // generated-model layer0 texture, if any
        String modelParent,       // generated-model parent (block/cube_all…)
        String displayName,
        List<NamespacedId> drops,
        boolean blastResistant,
        String movePolicy,        // keep | break
        NamespacedId placedBy,    // item that places this block, if declared
        List<Map<String, Object>> behavior, // ECA rules (runtime-systems §2), empty when none
        Map<String, Object> raw) {

    public BlockDefinition {
        drops = List.copyOf(drops);
        behavior = behavior == null ? List.of() : List.copyOf(behavior);
        raw = Map.copyOf(raw);
    }

    /** True when the definition asks for a generated model (auto-model path). */
    public boolean wantsGeneratedModel() {
        return texture != null && !texture.isBlank();
    }

    /** Merged view: raw fields with named accessors on top. */
    public Map<String, Object> merged() {
        var merged = new LinkedHashMap<String, Object>(raw);
        merged.put("technique", technique.name().toLowerCase(java.util.Locale.ROOT));
        if (displayName != null) {
            merged.put("display_name", displayName);
        }
        return merged;
    }
}
