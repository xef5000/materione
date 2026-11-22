package dev.materione.plugin.logic.world;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The caps of the world verbs (logic.md §41): one set of numbers that the actions, the tests and the docs read. The defaults
 * are chosen so the worst legal step is cheap; a server owner moves any of them in {@code settings.yml} (flat keys
 * {@code world_verbs_*}, optional, not written to a fresh file). A value is checked when the settings load: one that is not a
 * whole number inside the hard range falls back to its default and says so ({@code MTR-0121}); nothing else about the
 * server is affected.
 *
 * @param radius the largest radius of a {@code break_blocks} box on one axis (a step names at most (2r+1)^3 blocks)
 * @param blocksPerRun the most blocks one run may change across {@code break_blocks} and {@code set_block}
 * @param particlesPerStep the most particles one {@code particle} step may spawn
 * @param particlesPerRun the most particles one run may spawn
 * @param launchesPerStep the most projectiles one {@code launch} step may launch
 * @param launchesPerRun the most projectiles one run may launch
 * @param dropStacksPerRun the most item stacks one run may drop with {@code drop_item}
 */
public record WorldLimits(int radius, long blocksPerRun, int particlesPerStep, long particlesPerRun,
                          int launchesPerStep, long launchesPerRun, long dropStacksPerRun) {

    /** What a server gets when it writes nothing. */
    public static final WorldLimits DEFAULTS = new WorldLimits(4, 2_048, 500, 2_000, 16, 16, 128);

    /** The farthest a verb-raised event chain may nest before the bridges stop answering it. */
    public static final int MAX_CHAIN_DEPTH = 4;

    /** One settings key: its name, its hard range and its default. */
    private record Key(String name, long min, long max, long fallback) {}

    private static final Key[] KEYS = {
        new Key("world_verbs_max_radius", 1, 8, DEFAULTS.radius),
        new Key("world_verbs_max_blocks_per_run", 1, 65_536, DEFAULTS.blocksPerRun),
        new Key("world_verbs_max_particles_per_step", 1, 5_000, DEFAULTS.particlesPerStep),
        new Key("world_verbs_max_particles_per_run", 1, 100_000, DEFAULTS.particlesPerRun),
        new Key("world_verbs_max_launches_per_step", 1, 64, DEFAULTS.launchesPerStep),
        new Key("world_verbs_max_launches_per_run", 1, 256, DEFAULTS.launchesPerRun),
        new Key("world_verbs_max_drop_stacks_per_run", 1, 4_096, DEFAULTS.dropStacksPerRun),
    };

    /** The most blocks one {@code break_blocks} step may name. */
    public long blocksPerStep() {
        long side = 2L * radius + 1;
        return side * side * side;
    }

    /**
     * The limits {@code settings} write. A key that is absent is the default; one that is present and is not a whole number inside
     * its range is the default too, with one warning ({@code MTR-0121}) naming the key and the range.
     */
    public static WorldLimits read(Map<String, Object> settings, Consumer<Diagnostic> diagnostics) {
        long[] v = new long[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            Key key = KEYS[i];
            Object raw = settings.get(key.name);
            v[i] = key.fallback;
            if (raw == null) {
                continue;
            }
            if (raw instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                    && number.longValue() >= key.min && number.longValue() <= key.max) {
                v[i] = number.longValue();
            } else if (diagnostics != null) {
                diagnostics.accept(new Diagnostic(DiagnosticCodes.VERB_SETTING_INVALID, Severity.WARN,
                        "setting " + key.name + " is " + raw + ", which is not a whole number from " + key.min + " to "
                                + key.max + "; the default " + key.fallback + " is used", null, null,
                        "Fix the value in settings.yml, or remove the key to use the default."));
            }
        }
        return new WorldLimits((int) v[0], v[1], (int) v[2], v[3], (int) v[4], v[5], v[6]);
    }
}
