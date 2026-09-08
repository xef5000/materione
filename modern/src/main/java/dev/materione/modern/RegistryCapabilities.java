package dev.materione.modern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runtime feature detection for the 26.x-era registry APIs — reflection on
 * the <em>server's own</em> classes, never version-string parsing. The
 * decision points mirror what the code actually needs:
 *
 * <ul>
 *   <li>{@link #hasCompose()} — painting variants can register through the
 *       registry lifecycle (the {@code compose()} event type exists only on
 *       1.21.6+; 1.21.4/1.21.5 know only the {@code freeze()} predecessor
 *       this module does not use)</li>
 *   <li>{@link #hasJukeboxSong()} — jukebox songs can register through the
 *       registry lifecycle ({@code RegistryEvents.JUKEBOX_SONG} exists only
 *       on 1.21.6+)</li>
 * </ul>
 *
 * <p>Both classes are present on every supported server, so these probes
 * never throw at runtime — absence of a member is a normal, expected
 * condition that selects the world-datapack fallback instead.
 */
public final class RegistryCapabilities {

    private RegistryCapabilities() {}

    /** True when registry entries can be composed (Paper 1.21.6+). */
    public static boolean hasCompose() {
        try {
            Method compose = Class.forName(REGISTRY_EVENT_PROVIDER).getMethod("compose");
            return compose != null;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    /** True when jukebox songs have a registry compose event (Paper 1.21.6+). */
    public static boolean hasJukeboxSong() {
        try {
            Field jukebox = Class.forName(REGISTRY_EVENTS).getField("JUKEBOX_SONG");
            return jukebox != null;
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            return false;
        }
    }

    /**
     * The full registry seam: paintings and songs both register through the
     * lifecycle and no world datapack is needed. A hypothetical server with
     * only one of the two capabilities stays on the datapack path for
     * everything — the artifact carries both, and half-registering would
     * collide with the datapack's own entries at compose time.
     */
    public static boolean hasFullRegistrySeam() {
        return hasCompose() && hasJukeboxSong();
    }

    private static final String REGISTRY_EVENT_PROVIDER =
            "io.papermc.paper.registry.event.RegistryEventProvider";
    private static final String REGISTRY_EVENTS =
            "io.papermc.paper.registry.event.RegistryEvents";
}
