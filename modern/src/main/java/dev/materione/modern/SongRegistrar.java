package dev.materione.modern;

import dev.materione.content.sounds.SoundDefinition;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.JukeboxSongKeys;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Registers every parsed jukebox song through Paper's registry compose
 * event, replacing the datapack {@code jukebox_song} artifacts on 1.21.6+.
 *
 * <p>The song's sound event is registered <em>inline</em> through the
 * nested builder factory — a custom, resource-pack-only sound key is not a
 * registry entry of its own, and there is no SOUND_EVENT registry event;
 * {@code factory.empty().location(key)} declares the sound event as part of
 * the song (the Nexo pattern, verified against their decompiled sources).
 *
 * <p>This class references 26.x-era APIs ({@code JUKEBOX_SONG}); it must
 * only be entered when {@link RegistryCapabilities#hasJukeboxSong()} is
 * true — the gate lives in {@link MaterioneBootstrap}.
 */
final class SongRegistrar {

    private SongRegistrar() {}

    /** Registers the compose handler; returns the number of songs in scope. */
    static int register(BootstrapContext context, List<SoundDefinition> songs) {
        if (songs.isEmpty()) {
            return 0;
        }
        context.getLifecycleManager().registerEventHandler(
                RegistryEvents.JUKEBOX_SONG.compose().newHandler(event -> {
                    for (SoundDefinition song : songs) {
                        try {
                            event.registry().register(
                                    JukeboxSongKeys.create(Key.key(song.id().toString())),
                                    builder -> builder
                                            .description(Component.text(descriptionOf(song)))
                                            .lengthInSeconds(lengthOf(song))
                                            .comparatorOutput(comparatorOf(song))
                                            .soundEvent(factory -> {
                                                var sound = factory.empty();
                                                sound.location(Key.key(song.id().toString()));
                                                if (rangeOf(song) > 0) {
                                                    sound.fixedRange(rangeOf(song));
                                                }
                                            }));
                        } catch (RuntimeException e) {
                            context.getLogger().warn(
                                    "Failed to register jukebox song " + song.id() + ": " + e.getMessage());
                        }
                    }
                }));
        return songs.size();
    }

    /** Mirrors {@code SoundType.jukeboxSongJson} — same fields, same defaults. */
    private static String descriptionOf(SoundDefinition song) {
        Object description = song.jukebox().get("description");
        return description instanceof String text && !text.isBlank() ? text : song.id().value();
    }

    private static float lengthOf(SoundDefinition song) {
        Object duration = song.jukebox().get("duration");
        float length = duration instanceof Number number ? number.floatValue() : 120.0f;
        return Math.max(1.0f, length);
    }

    private static int comparatorOf(SoundDefinition song) {
        Object comparator = song.jukebox().get("comparator_output");
        int value = comparator instanceof Number number ? number.intValue() : 15;
        return Math.max(0, Math.min(15, value));
    }

    private static float rangeOf(SoundDefinition song) {
        Object range = song.jukebox().get("range");
        return range instanceof Number number ? number.floatValue() : -1;
    }
}
