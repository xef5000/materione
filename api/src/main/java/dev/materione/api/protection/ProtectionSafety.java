package dev.materione.api.protection;

/**
 * The thread a {@link ProtectionPort} may be asked on, declared by the provider (protection.md section 5, D8). It is the
 * protection port's version of what a currency backend declares: the service reads it before it asks, and it is cheap and
 * safe to read from any thread.
 */
public enum ProtectionSafety {

    /**
     * The provider is safe on any thread: it reads only data that is immutable or concurrent. The service asks it where
     * the caller stands, without hopping.
     */
    ANY_THREAD,

    /**
     * The provider reads the world or the plugin's data of the asked location, and is asked on the thread that owns that
     * location (the region thread on Folia, the main thread on Paper). The default, and the right answer for almost
     * every claim plugin.
     */
    OWNING_REGION,

    /**
     * The provider must be asked on the main thread (a plugin that was never made region-aware). On Paper it is asked
     * like {@link #OWNING_REGION}; on Folia it is not installed, and {@code MTR-0124} says so.
     */
    MAIN_THREAD_ONLY
}
