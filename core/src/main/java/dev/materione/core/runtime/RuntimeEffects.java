package dev.materione.core.runtime;

import dev.materione.api.NamespacedId;
import java.util.Map;
import java.util.Optional;

/**
 * The text-effect registry (M4, runtime-systems §6): every live effect
 * with the signal color the compiler allocated for it. The runtime text
 * pipeline paints {@code <effect:…>} spans with the signal; the pack's
 * injected text vertex shader matches on it and animates. Built into the
 * shadow during the pipeline run and swapped atomically like
 * {@link RuntimeGlyphs}.
 */
public final class RuntimeEffects {

    public static final Class<RuntimeEffects> KEY = RuntimeEffects.class;

    /** One live effect. */
    public record Entry(
            NamespacedId id,
            String kind,
            float speed,
            float amplitude,
            int signalRgb) {}

    private final Map<NamespacedId, Entry> effects;

    public RuntimeEffects(Map<NamespacedId, Entry> effects) {
        this.effects = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(effects));
    }

    public static RuntimeEffects empty() {
        return new RuntimeEffects(Map.of());
    }

    /** All effect entries (insertion order). */
    public java.util.Collection<Entry> all() {
        return effects.values();
    }

    public Optional<Entry> effect(NamespacedId id) {
        return Optional.ofNullable(effects.get(id));
    }

    /** Effect lookup by a bare or full id string ({@code rainbow} / {@code pack:rainbow}). */
    public Optional<Entry> byId(String raw) {
        if (raw.contains(":")) {
            return Optional.ofNullable(effects.get(NamespacedId.parse(raw)));
        }
        return effects.values().stream()
                .filter(entry -> entry.id().value().equals(raw))
                .findFirst();
    }

    public boolean isEmpty() {
        return effects.isEmpty();
    }
}
