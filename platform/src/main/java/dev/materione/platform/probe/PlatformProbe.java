package dev.materione.platform.probe;

import dev.materione.api.platform.ProbeResult;
import dev.materione.platform.PlatformDescriptors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/**
 * Probes the running server and enforces the version floor (D10).
 *
 * <p>This is the one place that calls {@code Bukkit.getBukkitVersion()} —
 * the ArchUnit rule R2 bans that call everywhere else.
 */
public final class PlatformProbe {

    /** The D10 floor: the oldest server Materione supports. */
    public static final String FLOOR = "1.21.4";

    private static final Pattern VERSION_PREFIX =
            Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private PlatformProbe() {}

    /** Probes the live server. */
    public static ProbeResult probeServer() {
        return probe(
                Bukkit.getBukkitVersion(),
                detectFoliaServer(),
                Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")
                        || Bukkit.getPluginManager().isPluginEnabled("Geyser"),
                Bukkit.getPluginManager().isPluginEnabled("ViaVersion"));
    }

    /**
     * Pure probe (unit-testable): parses a Bukkit version string like
     * {@code 1.21.4-232-main}, enforces the floor, and resolves the
     * descriptor on success.
     */
    public static ProbeResult probe(String bukkitVersion, boolean folia, boolean geyser, boolean via) {
        int[] version = parse(bukkitVersion);
        if (version == null) {
            return new ProbeResult.Unsupported(
                    "unrecognizable server version string '" + bukkitVersion + "'",
                    "report this at https://materione.dev — include the full version string");
        }
        int[] floor = parse(FLOOR);
        if (compare(version, floor) < 0) {
            return new ProbeResult.Unsupported(
                    "found " + format(version) + ", but Materione requires " + FLOOR + " or newer (D10)",
                    "update your Paper server to " + FLOOR
                            + "+; Materione deliberately does not support older servers");
        }
        return new ProbeResult.Supported(
                PlatformDescriptors.resolve(format(version), folia, geyser, via));
    }

    /**
     * Detects the Folia server flavor by probing for Folia-only region
     * internals (Paper ships the scheduler API but not the regionizer).
     */
    public static boolean detectFoliaServer() {
        return classExists("io.papermc.paper.threadedregions.ThreadedRegionizer")
                || classExists("io.papermc.paper.threadedregions.RegionizedServer");
    }

    /** Parses {@code 1.21.4-232-main} → {@code [1, 21, 4]}; null if unparseable. */
    static int[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = VERSION_PREFIX.matcher(raw.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)),
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    static String format(int[] version) {
        return version[2] == 0
                ? version[0] + "." + version[1]
                : version[0] + "." + version[1] + "." + version[2];
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, PlatformProbe.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
