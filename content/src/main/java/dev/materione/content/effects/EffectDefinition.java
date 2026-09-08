package dev.materione.content.effects;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Map;

/**
 * A parsed text-effect definition (runtime-systems §6): one animated text
 * style (rainbow, wave, shake, pulse) rendered by the pack's injected
 * {@code rendertype_text} shader. The definition is the stable surface —
 * the shader emission is version-gated (D10) and lands only when the
 * running client's shader format is a verified target.
 */
public record EffectDefinition(
        NamespacedId id,
        String kind,
        float speed,
        float amplitude,
        List<String> colors) {

    /** Kinds the runtime text pipeline and shader set understand. */
    public static final List<String> KINDS = List.of("rainbow", "wave", "shake", "pulse");
}
