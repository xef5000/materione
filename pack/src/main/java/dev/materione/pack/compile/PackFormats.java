package dev.materione.pack.compile;

import java.util.Map;
import team.unnamed.creative.metadata.pack.FormatVersion;
import team.unnamed.creative.metadata.pack.PackFormat;

/**
 * Pack-format policy for emitted packs. The pack advertises
 * {@code pack_format} at the 1.21.4 floor plus a range up to the newest
 * known format — clients between the two accept the pack without
 * per-version overlays, including versions missing from the table below
 * (the range, not the table, is what clients check).
 *
 * <p>The range is emitted in <em>both</em> schema generations: creative's
 * codec writes {@code pack_format} + {@code supported_formats} (read by
 * clients before the 25w31a schema change) and {@code min_format} +
 * {@code max_format} (read by 1.21.9+/26.x clients, which ignore
 * {@code supported_formats} — a pack advertising only the old fields shows
 * as incompatible on every current client).
 *
 * <p>The known-version table exists so the plugin can warn when neither
 * the table nor the server's own report covers the running version
 * ({@link #isKnown}); the server-reported format (read from the server
 * jar's {@code version.json}, platform layer) takes precedence when
 * available via {@link #spec(int)}. This class stays pure (plugin
 * architecture §4.4).
 */
public final class PackFormats {

    /** Pack format of the 1.21.4 Materione floor (D10). */
    public static final int FLOOR = 46;

    /** Newest Minecraft version whose format is known to this build. */
    public static final String MAX_KNOWN_VERSION = "26.2";

    /** Pack format of {@link #MAX_KNOWN_VERSION}; the default upper bound. */
    public static final int MAX_KNOWN_FORMAT = 88;

    /**
     * Known server versions → their resource-pack format. Values verified
     * against the Nexo overlay matrix shipped in {@code nexo-reference/}
     * and the Minecraft wiki pack-format history where noted; maintain per
     * release. Patch releases ride their minor line's format (a patch bump
     * without a format change is the norm — "26.1.2" matches "26.1").
     */
    private static final Map<String, Integer> KNOWN = Map.ofEntries(
            Map.entry("1.21.4", 46),
            Map.entry("1.21.5", 55),
            Map.entry("1.21.6", 63),
            // 1.21.7/1.21.8 kept the format of 1.21.6's successor bump
            Map.entry("1.21.7", 64),
            Map.entry("1.21.8", 64),
            Map.entry("1.21.11", 75), // nexo-reference overlay V1_21_11
            Map.entry("26.1", 84), // nexo-reference overlay V26_1; 26.1.1/.2 share it
            Map.entry("26.2", 88)); // nexo-reference overlay V26_2

    private PackFormats() {}

    /** The format spec every emitted pack advertises (table-bounded). */
    public static PackFormat spec() {
        return spec(MAX_KNOWN_FORMAT);
    }

    /**
     * The spec with {@code detectedMaxFormat} folded into the upper bound —
     * the server's own reported format wins over the table so a server
     * newer than this build still accepts its pack. Never below the table
     * bound: older servers must not shrink what newer clients can accept.
     */
    public static PackFormat spec(int detectedMaxFormat) {
        int max = Math.max(MAX_KNOWN_FORMAT, detectedMaxFormat);
        return PackFormat.format(FormatVersion.of(FLOOR), FormatVersion.of(max));
    }

    /**
     * Whether the server version is in the known table (patch releases
     * included — {@code 26.1.2} rides {@code 26.1}). Unknown does not
     * change output by itself — the range still covers intermediate
     * formats — but the caller warns (MTR-0018) when the version is
     * unknown <em>and</em> the server-reported format is unavailable,
     * because then a server newer than {@link #MAX_KNOWN_FORMAT} would
     * reject its own pack.
     */
    public static boolean isKnown(String minecraftVersion) {
        if (minecraftVersion == null) {
            return false;
        }
        if (KNOWN.containsKey(minecraftVersion)) {
            return true;
        }
        int dot = minecraftVersion.lastIndexOf('.');
        return dot > 0 && KNOWN.containsKey(minecraftVersion.substring(0, dot));
    }
}
