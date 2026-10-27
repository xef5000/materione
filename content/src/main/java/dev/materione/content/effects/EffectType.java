package dev.materione.content.effects;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.CompileRejectException;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.EffectSpec;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.content.Templates;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The text-effect content type (M4, runtime-systems §6): parses and
 * validates {@code effects/*.yml} — rainbow, wave, shake, pulse.
 *
 * <p>Compile allocates each effect a durable signal color and hands an
 * {@link EffectSpec} to the pack compiler, which composes the injected
 * text-shader set (version-gated overlays, runtime-systems §6). The
 * runtime paints affected text spans with the signal; the shader matches
 * on it and applies the animation.
 */
public final class EffectType implements ContentType {

    private static final int MAX_COLORS = 16;

    @Override
    public String name() {
        return "effect";
    }

    @Override
    public String directory() {
        return "effects";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Text effect",
                "An animated text style (rainbow, wave, shake, pulse) applied with <effect:id> tags",
                List.of(
                        new ParameterSchema("kind", ParameterType.ENUM, true, null,
                                "Effect kind: rainbow, wave, shake or pulse", EffectDefinition.KINDS, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Kind", "identity", 10, false, "select", "", "rainbow")
                                        .withOptions(List.of(
                                                option("rainbow", "Rainbow", "Colours flow along the text"),
                                                option("wave", "Wave", "Letters bob up and down"),
                                                option("shake", "Shake", "Letters jitter"),
                                                option("pulse", "Pulse", "The text brightens and dims")))
                                        .onDisplayCard()),
                        new ParameterSchema("speed", ParameterType.DECIMAL, false, "3.0",
                                "Animation speed (cycles per second scale)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Speed", "appearance", 10, false, "number", "", "3.0").withRange(0.0, 20.0, "×").onDisplayCard()),
                        new ParameterSchema("amplitude", ParameterType.DECIMAL, false, "1.0",
                                "Effect strength (wave height, shake distance, pulse depth)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Strength", "appearance", 20, false, "number", "", "1.0").withRange(0.0, 10.0, "×").onDisplayCard()),
                        new ParameterSchema("colors", ParameterType.LIST, false, null,
                                "Rainbow gradient stops as #RRGGBB (rainbow kind)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Palette", "appearance", 30, false, "palette", "", "#FF0000",
                                        true, "appearance", "palette",
                                        "The colours a rainbow flows through; without any it cycles the full spectrum"))),
                "1.0.0");
    }

    private static ParameterSchema.EditorOption option(String value, String label, String description) {
        return ParameterSchema.EditorOption.of(value, label, description);
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty();
        }
        String kind = Templates.text(merged, "kind");
        if (kind == null || !EffectDefinition.KINDS.contains(kind.toLowerCase(java.util.Locale.ROOT))) {
            return Optional.empty();
        }
        List<String> colors = stringList(merged.get("colors"));
        if (colors.size() > MAX_COLORS) {
            return Optional.empty();
        }
        for (String color : colors) {
            if (!color.matches("#[0-9a-fA-F]{6}")) {
                return Optional.empty();
            }
        }
        return Optional.of(new EffectDefinition(
                id,
                kind.toLowerCase(java.util.Locale.ROOT),
                floatOf(merged, "speed", 3.0f),
                floatOf(merged, "amplitude", 1.0f),
                colors));
    }

    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        if (!(definition instanceof EffectDefinition effect)) {
            return;
        }
        // durable signal color from the allocation table (D7) — the shader
        // keys the effect on this exact RGB, so it must never shift
        int slot;
        try {
            slot = Integer.parseInt(allocations.allocate(effect.id(), ALLOCATION_KIND));
        } catch (NumberFormatException e) {
            throw new CompileRejectException(
                    DiagnosticCodes.EFFECT_SIGNAL_EXHAUSTED,
                    "text effect '" + effect.id() + "' has a corrupt signal allocation",
                    "delete storage/allocation.bin's entry for this effect (or the whole table "
                            + "on a fresh server) and reload");
        }
        // the reserved band is #FF00FF downward: blue channel 255 (the literal
        // full magenta) stays free, 254 usable slots
        if (slot < 1 || slot > MAX_SIGNALS) {
            throw new CompileRejectException(
                    DiagnosticCodes.EFFECT_SIGNAL_EXHAUSTED,
                    "text effect '" + effect.id() + "' needs signal slot " + slot
                            + " but the band holds " + MAX_SIGNALS,
                    "remove or merge effect definitions; each definition permanently "
                            + "occupies one signal color");
        }
        emitter.textEffect(new EffectSpec(
                effect.id(),
                effect.kind(),
                effect.speed(),
                effect.amplitude(),
                effect.colors(),
                SIGNAL_BASE | (0xFF - slot)));
    }

    /** Allocation kind for the per-effect signal color (blue channel of #FF00FF downward). */
    public static final String ALLOCATION_KIND = "effect-signal";

    private static final int MAX_SIGNALS = 254;
    /** The red+green half of the band: blue carries the slot (#FF00FF downward). */
    private static final int SIGNAL_BASE = 0xFF0000;

    // --- helpers -------------------------------------------------------------

    private static float floatOf(Map<String, Object> map, String key, float fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (Object entry : list) {
            if (entry instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}
