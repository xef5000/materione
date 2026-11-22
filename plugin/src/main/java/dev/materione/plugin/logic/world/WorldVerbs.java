package dev.materione.plugin.logic.world;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.RunMeter;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.protection.ProtectionService;
import dev.materione.plugin.protection.ProtectionSettings;
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
 * <p>The caps are {@link WorldLimits}: one set of numbers that the actions, the tests and the docs read, overridable in
 * {@code settings.yml}.
 */
public final class WorldVerbs {

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
    private final DamageWatch damageWatch;
    private final WorldLimits limits;
    private final Consumer<Diagnostic> diagnostics;
    private final LongSupplier clock;
    private final Map<String, Long> reported = new ConcurrentHashMap<>();
    private final ProtectionService protection;
    private volatile CustomBlocks custom;

    /** A platform whose protection is the event path alone (mode {@code event}, no provider): the shape before the port. */
    public WorldVerbs(Regions regions, BlockOps blocks, ProjectileLauncher projectiles, Function<String, World> worlds,
            Function<NamespacedKey, Particle> particles, Function<String, BlockData> blockData,
            DamageWatch damageWatch, WorldLimits limits, Consumer<Diagnostic> diagnostics, LongSupplier clock) {
        this(regions, blocks, projectiles, worlds, particles, blockData, damageWatch, limits, diagnostics, clock,
                ProtectionService.inert());
    }

    /** @param protection the protection port every verb that changes the world asks (protection.md) */
    public WorldVerbs(Regions regions, BlockOps blocks, ProjectileLauncher projectiles, Function<String, World> worlds,
            Function<NamespacedKey, Particle> particles, Function<String, BlockData> blockData,
            DamageWatch damageWatch, WorldLimits limits, Consumer<Diagnostic> diagnostics, LongSupplier clock,
            ProtectionService protection) {
        this.protection = protection;
        this.regions = regions;
        this.blocks = blocks;
        this.projectiles = projectiles;
        this.worlds = worlds;
        this.particles = particles;
        this.blockData = blockData;
        this.damageWatch = damageWatch;
        this.limits = limits;
        this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
        this.clock = clock;
    }

    /** The server-backed platform with the default caps. */
    public static WorldVerbs server(Plugin plugin, SchedulingGateway gateway, Consumer<Diagnostic> diagnostics) {
        return server(plugin, gateway, diagnostics, WorldLimits.DEFAULTS);
    }

    /**
     * The server-backed platform: Paper ownership and hops through the scheduling gateway, the server's block and launch paths,
     * and a {@link DamageWatch.Paper} the caller registers as a listener ({@link #damageWatch}).
     */
    public static WorldVerbs server(Plugin plugin, SchedulingGateway gateway, Consumer<Diagnostic> diagnostics,
            WorldLimits limits) {
        Regions regions = Regions.server(plugin, gateway);
        return server(plugin, gateway, diagnostics, limits, new ProtectionService(ProtectionSettings.EVENT_ONLY, regions,
                false, diagnostics, System::currentTimeMillis));
    }

    /** As above, with the protection service the verbs ask (built from the {@code protection_*} settings). */
    public static WorldVerbs server(Plugin plugin, SchedulingGateway gateway, Consumer<Diagnostic> diagnostics,
            WorldLimits limits, ProtectionService protection) {
        WorldVerbs[] self = new WorldVerbs[1];
        var verbs = new WorldVerbs(Regions.server(plugin, gateway), new PaperBlockOps(() -> self[0].custom),
                ProjectileLauncher.server(), Bukkit::getWorld, key -> Registry.PARTICLE_TYPE.get(key),
                Bukkit::createBlockData, new DamageWatch.Paper(), limits, diagnostics, System::currentTimeMillis,
                protection);
        self[0] = verbs;
        return verbs;
    }

    /**
     * The protection port the verbs ask before they change the world on someone's behalf (protection.md): the mode, the
     * actor-less switch and the providers.
     */
    public ProtectionService protection() {
        return protection;
    }

    /** The caps of this server. */
    public WorldLimits limits() {
        return limits;
    }

    /** What {@code damage} asks to learn whether its blow was vetoed; the server's is a listener to register. */
    public DamageWatch damageWatch() {
        return damageWatch;
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
                "On Folia a block changed as the player must lie in the region the player is in; keep the area small, "
                        + "use as_player: false, or set protection_mode: port, where the port answers on the block's region."));
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
