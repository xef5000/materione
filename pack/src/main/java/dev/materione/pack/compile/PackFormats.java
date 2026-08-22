package dev.materione.pack.compile;

import java.util.Map;
import team.unnamed.creative.metadata.pack.PackFormat;

/**
 * Pack-format policy for emitted packs. The pack advertises
 * {@code pack_format} at the 1.21.4 floor plus a {@code supported_formats}
 * range up to the newest known format — clients between the two accept the
 * pack without per-version overlays, including versions missing from the
 * table below (the range, not the table, is what clients check).
 *
 * <p>The known-version table exists so the plugin can warn when the running
 * server is newer than everything we know ({@link #isKnown}): its own
 * clients would fall outside the advertised range until the table is raised.
 * The platform layer owns version knowledge and publishes that warning;
 * this class stays pure (plugin architecture §4.4).
 */
public final class PackFormats {

    /** Pack format of the 1.21.4 Materione floor (D10). */
    public static final int FLOOR = 46;

    /** Newest Minecraft version whose format is known to this build. */
    public static final String MAX_KNOWN_VERSION = "26.2";

    /** Pack format of {@link #MAX_KNOWN_VERSION}; the advertised upper bound. */
    public static final int MAX_KNOWN_FORMAT = 88;

    /**
     * Known server versions → their resource-pack format. Values verified
     * against the Nexo overlay matrix shipped in {@code nexo-reference/}
     * where noted; maintain per release.
     */
    private static final Map<String, Integer> KNOWN = Map.ofEntries(
            Map.entry("1.21.4", 46),
            Map.entry("1.21.5", 55),
            Map.entry("1.21.6", 63),
            // 1.21.7/1.21.8 kept the format of 1.21.6's successor bump
            Map.entry("1.21.7", 64),
            Map.entry("1.21.8", 64),
            Map.entry("1.21.11", 75), // nexo-reference overlay V1_21_11
            Map.entry("26.1", 84), // nexo-reference overlay V26_1
            Map.entry("26.2", 88)); // nexo-reference overlay V26_2

    private static final PackFormat SPEC = PackFormat.format(FLOOR, FLOOR, MAX_KNOWN_FORMAT);

    private PackFormats() {}

    /** The format spec every emitted pack advertises. */
    public static PackFormat spec() {
        return SPEC;
    }

    /**
     * Whether the server version is in the known table. Unknown does not
     * change output — the range still covers intermediate formats — but the
     * caller warns (MTR-0018) because a server newer than
     * {@link #MAX_KNOWN_FORMAT} would reject its own pack.
     */
    public static boolean isKnown(String minecraftVersion) {
        return minecraftVersion != null && KNOWN.containsKey(minecraftVersion);
    }
}
