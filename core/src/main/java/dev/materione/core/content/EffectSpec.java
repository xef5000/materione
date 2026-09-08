package dev.materione.core.content;

import dev.materione.api.NamespacedId;
import java.util.List;

/**
 * One compiled text effect, handed from the content type to the pack
 * compiler (runtime-systems §6): the shader composition bakes the
 * parameters as GLSL constants and keys the effect on its allocated
 * <strong>signal color</strong> — the runtime paints affected text spans
 * with this exact color and the injected text vertex shader matches on it.
 *
 * <p>Signals live in the reserved band {@code #FF00FF} downward (blue
 * channel 254..1, one effect each): colors no vanilla chat style produces,
 * but which a user could still type by hand — the same trade-off the
 * incumbents make for their magic glyph colors.
 *
 * @param signalRgb RGB signal color, {@code 0xFF00FE}..{@code 0xFF0001}
 *        (alpha channel unused)
 */
public record EffectSpec(
        NamespacedId id,
        String kind,
        float speed,
        float amplitude,
        List<String> colors,
        int signalRgb) {

    public EffectSpec {
        colors = List.copyOf(colors);
    }
}
