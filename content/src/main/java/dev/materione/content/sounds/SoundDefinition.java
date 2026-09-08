package dev.materione.content.sounds;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Map;

/**
 * A parsed custom sound (runtime-systems §10): one sounds.json event
 * grouping one or more .ogg sources. Source refs are resolved to
 * {@code ns:path} (extension stripped) at parse; the path is relative to the
 * pack's {@code assets/sounds/} directory.
 *
 * <p>{@code replaceVanilla} names a vanilla sound event — the definition is
 * emitted into {@code assets/minecraft/sounds.json} through the compiler's
 * override merge instead of the pack's own namespace. {@code jukebox} (raw
 * map) turns the sound into a music-disc song (datapack artifact).
 */
public record SoundDefinition(
        NamespacedId id,
        List<String> sources,
        String category,
        float volume,
        float pitch,
        int weight,
        boolean stream,
        boolean preload,
        Integer attenuationDistance,
        String subtitle,
        boolean replace,
        String replaceVanilla,
        Map<String, Object> jukebox) {

    /** True when this definition also produces a jukebox song. */
    public boolean isSong() {
        return jukebox != null && !jukebox.isEmpty();
    }
}
