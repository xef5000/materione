package dev.materione.plugin.logic.world;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.RunMeter;
import dev.materione.api.scheduling.SchedulingGateway;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

/**
 * The platform behind the world verbs (logic.md §41): the region seam, the block and projectile seams, the custom-block
 * runtime (bound once content is applied), the world lookup, and the reporter of the two diagnostics a verb raises
 * ({@code MTR-0118}, {@code MTR-0119}). One instance is built at boot and handed to the actions that need it; a test
 * builds one from fakes.
 *
 * <p>The caps live here too, so the actions, the tests and the docs read one set of numbers.
 */
public final class WorldVerbs {

    /** The largest radius of a {@code break_blocks} box on one axis. */
    public static final int MAX_RADIUS = 4;
    /** The most blocks one {@code break_blocks} step may name: a 9 by 9 by 9 box. */
    public static final int MAX_BLOCKS_PER_STEP = 729;
    /** The most blocks one run may change across its {@code break_blocks} and {@code set_block} steps. */
    public static final long MAX_BLOCKS_PER_RUN = 2_048;
    /** The most particles one {@code particle} step may spawn. */
    public static final int MAX_PARTICLES_PER_STEP = 500;
    /** The most particles one run may spawn. */
    public static final long MAX_PARTICLES_PER_RUN = 2_000;
    /** The most projectiles one {@code launch} step may launch. */
    public static final int MAX_LAUNCH_PER_STEP = 16;
    /** The most projectiles one run may launch. */
    public static final long MAX_LAUNCHES_PER_RUN = 16;
    /** The most item stacks one run may drop into the world with {@code drop_item}. */
    public static final long MAX_DROP_STACKS_PER_RUN = 128;
    /** The farthest an {@code at} offset may reach on one axis, in blocks. */
    public static final double MAX_OFFSET = 64;

    /** The meter key {@code break_blocks} and {@code set_block} charge. */
    public static final String BLOCKS = "world.blocks";
    /** The meter key {@code drop_item} charges, in stacks. */
    public static final String DROPS = "world.drops";
    /** The meter key {@code particle} charges. */
    public static final String PARTICLES = "world.particles";
    /** The meter key {@code launch} charges. */
    public static final String LAUNCHES = "world.launches";

    /** Minimum milliseconds between two reports of one kind. */
    static final long REPORT_INTERVAL_MS = 10_000;

    private final Regions regions;
    private final BlockOps blocks;
    private final ProjectileLauncher projectiles;
    private final Function<String, World> worlds;
    private final Function<NamespacedKey, Particle> particles;
    private final Function<String, BlockData> blockData;
    private final Consumer<Diagnostic> diagnostics;
    private final LongSupplier clock;
    private final Map<String, Long> reported = new ConcurrentHashMap<>();
    private volatile CustomBlocks custom;

    public WorldVerbs(Regions regions, BlockOps blocks, ProjectileLauncher projectiles, Function<String, World> worlds,
            Function<NamespacedKey, Particle> particles, Function<String, BlockData> blockData,
            Consumer<Diagnostic> diagnostics, LongSupplier clock) {
        this.regions = regions;
        this.blocks = blocks;
        this.projectiles = projectiles;
        this.worlds = worlds;
        this.particles = particles;
        this.blockData = blockData;
        this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
        this.clock = clock;
    }

    /** The server-backed platform: Paper ownership and hops through {@code gateway}, the server's block and launch paths. */
    public static WorldVerbs server(Plugin plugin, SchedulingGateway gateway, Consumer<Diagnostic> diagnostics) {
        WorldVerbs[] self = new WorldVerbs[1];
        var verbs = new WorldVerbs(Regions.server(plugin, gateway), new PaperBlockOps(() -> self[0].custom),
                ProjectileLauncher.server(), Bukkit::getWorld, key -> Registry.PARTICLE_TYPE.get(key), Bukkit::createBlockData, diagnostics, System::currentTimeMillis);
        self[0] = verbs;
        return verbs;
    }

    public Regions regions() {
        return regions;
    }

    public BlockOps blocks() {
        return blocks;
    }

    public ProjectileLauncher projectiles() {
        return projectiles;
    }

    /** The world named {@code name}, or null. */
    public World world(String name) {
        return worlds.apply(name);
    }

    /** The particle with this key, or null (the server's registry; a test supplies its own). */
    public Particle particle(NamespacedKey key) {
        return particles.apply(key);
    }

    /**
     * The block data a vanilla block string names ({@code minecraft:oak_stairs[facing=north]}).
     *
     * @throws IllegalArgumentException when it names no block
     */
    public BlockData blockData(String text) {
        return blockData.apply(text);
    }

    /** Binds the custom-block runtime once it exists; until then a Materione block id is "not available". */
    public void bindCustomBlocks(CustomBlocks blocks) {
        this.custom = blocks;
    }

    /** The custom-block runtime, when bound. */
    public Optional<CustomBlocks> customBlocks() {
        return Optional.ofNullable(custom);
    }

    /**
     * Charges {@code amount} to the run's meter under {@code key} and says whether the run is still within {@code cap}.
     * A context with no meter (not a run) is always within: only the step's own bound applies there.
     */
    public static boolean within(LogicContext context, String key, long amount, long cap) {
        Optional<RunMeter> meter = context.facet(RunMeter.FACET);
        return meter.isEmpty() || meter.get().add(key, amount) <= cap;
    }

    /** {@code MTR-0118}: {@code verb} spent its per-run budget of {@code cap} {@code unit}. */
    public void budgetSpent(String verb, long cap, String unit) {
        report("budget:" + verb, new Diagnostic(DiagnosticCodes.VERB_BUDGET_SPENT, Severity.WARN,
                "'" + verb + "' stopped: this run has used its budget of " + cap + " " + unit, null, null,
                "Lower the count or the shape, or split the work across runs; the caps are in logic.md section 41."));
    }

    /** {@code MTR-0119}: {@code verb} skipped {@code count} location(s) that another region owns. */
    public void regionRefused(String verb, int count) {
        report("region:" + verb, new Diagnostic(DiagnosticCodes.VERB_REGION_REFUSED, Severity.WARN,
                "'" + verb + "' skipped " + count + " location(s) owned by another region", null, null,
                "On Folia a block changed as the player must lie in the region the player is in; keep the area small "
                        + "or use as_player: false."));
    }

    private void report(String key, Diagnostic diagnostic) {
        long now = clock.getAsLong();
        boolean[] emit = {false};
        reported.compute(key, (k, last) -> {
            if (last == null || now - last >= REPORT_INTERVAL_MS) {
                emit[0] = true;
                return now;
            }
            return last;
        });
        if (emit[0]) {
            diagnostics.accept(diagnostic);
        }
    }
}
