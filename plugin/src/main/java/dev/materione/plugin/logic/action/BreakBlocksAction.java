package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
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
 * false} breaks without items. <b>{@code as_player: false}</b> is the world acting with no actor: nothing is asked, so a
 * pack that writes it takes that choice on purpose; it works in a run with no player.
 *
 * <p><b>Outcome.</b> OK when at least one block was broken or none was breakable (an excavator in a hollow). Failed when
 * blocks were eligible and none could be broken (all vetoed or in another region), with the counts, so a {@code require}
 * can answer "the dig was refused".
 *
 * <p><b>Budgets.</b> The shape is at most 9 by 9 by 9 (729 blocks) a step; a run changes at most
 * {@link WorldVerbs#MAX_BLOCKS_PER_RUN} blocks across {@code break_blocks} and {@code set_block} ({@code MTR-0118}).
 * A block in a region this thread does not own is skipped when it must be broken as the player ({@code MTR-0119}).
 */
public final class BreakBlocksAction implements ActionType<BreakBlocksAction.Params> {

    /** The parsed step. */
    public record Params(Optional<Triple> shape, LocationSpec at, boolean asPlayer, boolean drops) {}

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
                                "The box's radius in blocks per axis, 0 to " + WorldVerbs.MAX_RADIUS
                                        + ": one number, [x, y, z], or {x, y, z}; [1, 1, 1] is a 3 by 3 by 3 box",
                                null, null),
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
        return new Params(Triple.read(params, "shape", 0, WorldVerbs.MAX_RADIUS, true),
                LocationSpec.read(params, "at", LocationSpec.Anchor.EVENT, false),
                params.bool("as_player", true), params.bool("drops", true));
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
        CompletionStage<Optional<Triple>> shape = params.shape().isEmpty()
                ? CompletableFuture.completedFuture(Optional.empty())
                : params.shape().get().resolve(context, reads).thenApply(Optional::of);
        return shape.thenCompose(resolved -> params.at().resolveOffset(context, reads).thenCompose(at ->
                at.resolve(context, reads, verbs)).thenCompose(where -> {
            if (where.location() == null) {
                return Stages.failed(where.problem());
            }
            int[] radius = {0, 0, 0};
            if (resolved.isPresent()) {
                var values = resolved.get().values(context);
                if (values.isEmpty()) {
                    return Stages.failed("shape must be whole numbers from 0 to " + WorldVerbs.MAX_RADIUS);
                }
                radius = new int[] {(int) values.get().x(), (int) values.get().y(), (int) values.get().z()};
            }
            long volume = (2L * radius[0] + 1) * (2L * radius[1] + 1) * (2L * radius[2] + 1);
            if (!WorldVerbs.within(context, WorldVerbs.BLOCKS, volume, WorldVerbs.MAX_BLOCKS_PER_RUN)) {
                verbs.budgetSpent("break_blocks", WorldVerbs.MAX_BLOCKS_PER_RUN, "blocks");
                return Stages.failed("this run has changed its " + WorldVerbs.MAX_BLOCKS_PER_RUN + " blocks");
            }
            List<Location> cells = cells(where.location(), radius);
            Tally tally = new Tally();
            CompletionStage<Tally> done = params.asPlayer()
                    ? reads.on(threading(), () -> asPlayer(player, cells, params.drops(), tally))
                    : plain(cells, 0, params.drops(), tally);
            return done.thenApply(t -> outcome(t));
        }));
    }

    /** What happened to the blocks of one step. */
    static final class Tally {
        int broken;
        int denied;
        int failed;
        int refused;
    }

    private ActionOutcome outcome(Tally tally) {
        if (tally.refused > 0) {
            verbs.regionRefused("break_blocks", tally.refused);
        }
        if (tally.broken > 0 || tally.denied + tally.failed + tally.refused == 0) {
            return ActionOutcome.OK;
        }
        return ActionOutcome.failed("no block was broken (" + tally.denied + " refused by a listener, "
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

    private Tally asPlayer(Player player, List<Location> cells, boolean drops, Tally tally) {
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
            record(tally, verbs.blocks().breakAs(player, block, drops));
        }
        return tally;
    }

    private CompletionStage<Tally> plain(List<Location> cells, int index, boolean drops, Tally tally) {
        if (index >= cells.size()) {
            return CompletableFuture.completedFuture(tally);
        }
        Location cell = cells.get(index);
        if (!cell.getWorld().isChunkLoaded(cell.getBlockX() >> 4, cell.getBlockZ() >> 4)) {
            return plain(cells, index + 1, drops, tally);
        }
        return verbs.regions().at(cell, () -> {
            Block block = cell.getBlock();
            return eligible(block) ? verbs.blocks().breakPlain(block, drops) : null;
        }).thenCompose(result -> {
            if (result != null) {
                record(tally, result);
            }
            return plain(cells, index + 1, drops, tally);
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
