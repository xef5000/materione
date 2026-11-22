package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.RunMeter;
import dev.materione.api.logic.Threading;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.Stages;
import dev.materione.plugin.logic.support.Triple;
import dev.materione.plugin.logic.world.BlockOps;
import dev.materione.plugin.logic.world.LocationSpec;
import dev.materione.plugin.logic.world.VerbScope;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * {@code break_blocks: {shape: [1, 1, 1], at: event, as_player: true, drops: true}}: breaks the blocks of a box (logic.md
 * §41). {@code shape} is the box's radius on each axis, in blocks (one number for all three, {@code [x, y, z]}, or
 * {@code {x, y, z}}; each 0 to {@link WorldVerbs#MAX_RADIUS}), around {@code at} (default: the event's location, which for
 * an item's {@code break} is the broken block). Air, liquids and unbreakable blocks (bedrock, barriers) are left alone, and
 * so is the block of the break event whose rules are running, because vanilla finishes breaking that one itself.
 *
 * <p><b>As the player</b> (the default) each block goes through the player's own break path: protection and claim plugins
 * are asked as for a mined block, the tool's enchantments and durability apply, and a vetoed block stays. {@code drops:
 * false} breaks without items. With the protection port (logic.md §43) the port is asked first in {@code protection_mode:
 * both}, and alone in {@code port}. <b>{@code as_player: false}</b> is the world acting with no actor: in {@code event}
 * mode nothing is asked, and otherwise the port is asked with no actor, so a claim may still refuse it; it works in a run
 * with no player.
 *
 * <p><b>Outcome.</b> OK when at least one block was broken or none was breakable (an excavator in a hollow). Failed when
 * blocks were eligible and none could be broken (all vetoed or in another region), with the counts, so a {@code require}
 * can answer "the dig was refused".
 *
 * <p><b>Budgets.</b> The shape is at most 9 by 9 by 9 (729 blocks) a step; a run changes at most
 * {@link WorldVerbs#MAX_BLOCKS_PER_RUN} blocks across {@code break_blocks} and {@code set_block} ({@code MTR-0118}).
 * A block in a region this thread does not own is skipped when it must be broken as the player ({@code MTR-0119}), except in
 * {@code protection_mode: port}, where the port answers on the block's own region and the block is broken there.
 */
public final class BreakBlocksAction implements ActionType<BreakBlocksAction.Params> {

    /** The parsed step. */
    public record Params(Optional<Triple> shape, LocationSpec at, boolean asPlayer, boolean drops, Relative relativeTo) {}

    /** What the axes of {@code shape} are measured along. */
    enum Relative { WORLD, FACING }

    private final WorldVerbs verbs;

    /** @param verbs the platform: the region seam, the block seam, the world lookup and the budget reporter */
    public BreakBlocksAction(WorldVerbs verbs) {
        this.verbs = verbs;
    }

    @Override
    public String id() {
        return "break_blocks";
    }

    @Override
    public ActionDescription describe() {
        return ActionDescription.of(new SelfDescription("Break blocks",
                "Breaks the blocks of a box around a location, as the player by default so protection is asked",
                List.of(
                        new ParameterSchema("shape", ParameterType.RAW, false, "0",
                                "The box's radius in blocks per axis, 0 to " + verbs.limits().radius()
                                        + ": one number, [x, y, z], or {x, y, z}; [1, 1, 1] is a 3 by 3 by 3 box. With "
                                        + "relative_to: facing the axes are across, up and depth",
                                null, null),
                        new ParameterSchema("relative_to", ParameterType.ENUM, false, "world",
                                "world measures the shape along the world axes; facing measures it across and up from the "
                                        + "face the player hit (else the way they look), with z the depth: [1, 1, 0] is the "
                                        + "flat 3 by 3 in front of the player", List.of("world", "facing"), null),
                        new ParameterSchema("at", ParameterType.RAW, false, "event",
                                "The centre: event (the broken block, for a break rule), player, or {of, x, y, z}",
                                null, null),
                        new ParameterSchema("as_player", ParameterType.BOOLEAN, false, "true",
                                "Break through the player's own break path so protection is asked; false is the world "
                                        + "acting with no actor and asks nobody", null, null),
                        new ParameterSchema("drops", ParameterType.BOOLEAN, false, "true",
                                "Whether the blocks drop their items", null, null)),
                "2.0.0"), LogicCategory.WORLD);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(Triple.read(params, "shape", 0, verbs.limits().radius(), true),
                LocationSpec.read(params, "at", LocationSpec.Anchor.EVENT, false),
                params.bool("as_player", true), params.bool("drops", true),
                params.enumValue("relative_to", Relative.class, Relative.WORLD));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return Stages.now(executeResumable(params, context, ReadExecutor.INLINE));
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        Player player = LocationSpec.runPlayer(context).orElse(null);
        if (params.asPlayer() && player == null) {
            return Stages.failed("there is no player to break blocks as; write as_player: false for a run with none");
        }
        Object rule = context.facet(RunMeter.FACET).map(RunMeter::origin).orElse(null);
        String face = context.value("block.face") instanceof String text ? text : "";
        if (params.relativeTo() == Relative.FACING && face.isEmpty() && player == null) {
            return Stages.failed("relative_to: facing needs a player or a trigger that carries the face of a block");
        }
        CompletionStage<Location> looking = params.relativeTo() == Relative.FACING && face.isEmpty()
                ? reads.on(threading(), player::getLocation)
                : CompletableFuture.completedFuture(null);
        CompletionStage<Optional<Triple>> shape = params.shape().isEmpty()
                ? CompletableFuture.completedFuture(Optional.empty())
                : params.shape().get().resolve(context, reads).thenApply(Optional::of);
        return looking.thenCompose(look -> shape.thenCompose(resolved -> params.at().resolveOffset(context, reads)
                .thenCompose(at -> at.resolve(context, reads, verbs)).thenCompose(where -> {
            if (where.location() == null) {
                return Stages.failed(where.problem());
            }
            int[] radius = {0, 0, 0};
            if (resolved.isPresent()) {
                var values = resolved.get().values(context);
                if (values.isEmpty()) {
                    return Stages.failed("shape must be whole numbers from 0 to " + verbs.limits().radius());
                }
                radius = new int[] {(int) values.get().x(), (int) values.get().y(), (int) values.get().z()};
            }
            if (params.relativeTo() == Relative.FACING) {
                radius = oriented(radius, face, look);
            }
            long volume = (2L * radius[0] + 1) * (2L * radius[1] + 1) * (2L * radius[2] + 1);
            if (!WorldVerbs.within(context, WorldVerbs.BLOCKS, volume, verbs.limits().blocksPerRun())) {
                verbs.budgetSpent("break_blocks", verbs.limits().blocksPerRun(), "blocks");
                return Stages.failed("this run has changed its " + verbs.limits().blocksPerRun() + " blocks");
            }
            List<Location> cells = cells(where.location(), radius);
            Tally tally = new Tally();
            boolean drops = params.drops();
            CompletionStage<Tally> done;
            if (params.asPlayer() && verbs.protection().runsEventPath()) {
                done = reads.on(threading(), () -> asPlayer(player, cells, drops, tally, rule));
            } else if (params.asPlayer()) {
                // protection_mode: port. The port answers alone, so the player need not stand beside every block: each block is
                // asked and broken on its own region's thread, with the tool captured on the player's (protection.md section 5)
                done = reads.on(threading(), () -> {
                    var held = player.getInventory().getItemInMainHand();
                    return held == null ? null : held.clone();
                }).thenCompose(tool -> {
                    ProtectionActor actor = ProtectionActor.of(player);
                    return each(cells, 0, tally, rule,
                            block -> guarded(actor, block, tally, () -> verbs.blocks().breakWith(tool, block, drops)));
                });
            } else {
                ProtectionActor nobody = ProtectionActor.none();
                done = each(cells, 0, tally, rule,
                        block -> guarded(nobody, block, tally, () -> verbs.blocks().breakPlain(block, drops)));
            }
            return done.thenApply(t -> outcome(t));
        })));
    }

    /**
     * The radii along the world axes for a shape given across, up and depth: the depth axis is the axis of the face the player hit,
     * or, with no face, the axis they look along (vertical when they look steeply up or down). Across and up are the other two:
     * up stays the world's vertical unless the depth is vertical, and then across is X and up is Z.
     */
    static int[] oriented(int[] across, String face, Location look) {
        int depth;
        switch (face.toLowerCase(java.util.Locale.ROOT)) {
            case "up", "down" -> depth = 1;
            case "north", "south" -> depth = 2;
            case "east", "west" -> depth = 0;
            default -> {
                double yaw = Math.toRadians(look.getYaw());
                if (Math.abs(look.getPitch()) >= 60) {
                    depth = 1;
                } else {
                    depth = Math.abs(Math.sin(yaw)) > Math.abs(Math.cos(yaw)) ? 0 : 2;
                }
            }
        }
        int[] world = new int[3];
        world[depth] = across[2];
        int acrossAxis = depth == 0 ? 2 : 0;
        int upAxis = depth == 1 ? 2 : 1;
        world[acrossAxis] = across[0];
        world[upAxis] = across[1];
        return world;
    }

    /** What happened to the blocks of one step. */
    static final class Tally {
        int broken;
        int denied;
        int failed;
        int refused;
        /** What the first protection provider that refused a block said, or null when no provider did. */
        String denial;
    }

    private ActionOutcome outcome(Tally tally) {
        if (tally.refused > 0) {
            verbs.regionRefused("break_blocks", tally.refused);
        }
        if (tally.broken > 0 || tally.denied + tally.failed + tally.refused == 0) {
            return ActionOutcome.OK;
        }
        return ActionOutcome.failed("no block was broken (" + tally.denied + " "
                + (tally.denial == null ? "refused by a listener" : tally.denial) + ", "
                + tally.refused + " in another region, " + tally.failed + " could not be broken)");
    }

    /** The cells of the box around {@code centre}, the nearest first, so a step that is cut short keeps the middle. */
    static List<Location> cells(Location centre, int[] radius) {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -radius[0]; dx <= radius[0]; dx++) {
            for (int dy = -radius[1]; dy <= radius[1]; dy++) {
                for (int dz = -radius[2]; dz <= radius[2]; dz++) {
                    offsets.add(new int[] {dx, dy, dz});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));
        World world = centre.getWorld();
        List<Location> cells = new ArrayList<>(offsets.size());
        for (int[] offset : offsets) {
            cells.add(new Location(world, centre.getBlockX() + offset[0], centre.getBlockY() + offset[1],
                    centre.getBlockZ() + offset[2]));
        }
        return cells;
    }

    @SuppressWarnings("try") // the scope is entered for its lifetime; nothing reads it
    private Tally asPlayer(Player player, List<Location> cells, boolean drops, Tally tally, Object rule) {
        ProtectionActor actor = ProtectionActor.of(player);
        for (Location cell : cells) {
            if (!cell.getWorld().isChunkLoaded(cell.getBlockX() >> 4, cell.getBlockZ() >> 4)) {
                continue;
            }
            if (!verbs.regions().ownedHere(cell)) {
                tally.refused++;
                continue;
            }
            Block block = cell.getBlock();
            if (!eligible(block)) {
                continue;
            }
            // both: the port answers first and denies early; the event path below is the player's own break
            if (denied(actor, block, tally)) {
                tally.denied++;
                continue;
            }
            try (var scope = VerbScope.enter(rule)) {
                record(tally, verbs.blocks().breakAs(player, block, drops));
            }
        }
        return tally;
    }

    /** Whether the protection port, when this mode asks it, denies {@code actor} breaking {@code block}; says why in the tally. */
    private boolean denied(ProtectionActor actor, Block block, Tally tally) {
        if (!verbs.protection().asksPort()) {
            return false;
        }
        ProtectionAnswer answer = verbs.protection().canBreak(actor, block.getLocation());
        if (answer.denied() && tally.denial == null) {
            tally.denial = answer.describeDenial();
        }
        return answer.denied();
    }

    /** The port's question, then {@code action}: what one block does on the thread that owns it. */
    private BlockOps.Result guarded(ProtectionActor actor, Block block, Tally tally, Supplier<BlockOps.Result> action) {
        return denied(actor, block, tally) ? BlockOps.Result.DENIED : action.get();
    }

    /**
     * Each cell in turn, on the thread that owns it (a hop when this one does not), through {@code breaker}: the world acting with
     * no actor, or, in {@code protection_mode: port}, the player's tool after the port's answer.
     */
    @SuppressWarnings("try") // the scope is entered for its lifetime; nothing reads it
    private CompletionStage<Tally> each(List<Location> cells, int index, Tally tally, Object rule,
            Function<Block, BlockOps.Result> breaker) {
        if (index >= cells.size()) {
            return CompletableFuture.completedFuture(tally);
        }
        Location cell = cells.get(index);
        if (!cell.getWorld().isChunkLoaded(cell.getBlockX() >> 4, cell.getBlockZ() >> 4)) {
            return each(cells, index + 1, tally, rule, breaker);
        }
        return verbs.regions().at(cell, () -> {
            Block block = cell.getBlock();
            if (!eligible(block)) {
                return null;
            }
            try (var scope = VerbScope.enter(rule)) {
                return breaker.apply(block);
            }
        }).thenCompose(result -> {
            if (result != null) {
                record(tally, result);
            }
            return each(cells, index + 1, tally, rule, breaker);
        });
    }

    private static void record(Tally tally, BlockOps.Result result) {
        switch (result) {
            case DONE -> tally.broken++;
            case DENIED -> tally.denied++;
            case FAILED -> tally.failed++;
        }
    }

    /** Whether the verb may try {@code block}: not air, not a liquid, not unbreakable, not the event's own block. */
    private boolean eligible(Block block) {
        return verbs.blocks().breakable(block) && !VerbScope.isEventBlock(block);
    }
}
