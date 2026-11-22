package dev.materione.plugin.logic.action;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.RunMeter;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.Stages;
import dev.materione.plugin.logic.world.BlockOps;
import dev.materione.plugin.logic.world.CustomBlocks;
import dev.materione.plugin.logic.world.LocationSpec;
import dev.materione.plugin.logic.world.VerbScope;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * {@code set_block: {block: minecraft:glass, at: {of: player, y: -1}}}: replaces one block (logic.md §41). {@code block}
 * is a vanilla block ({@code stone}, {@code minecraft:oak_stairs[facing=north]}) or a live Materione block id
 * ({@code mypack:ruby_ore}); {@code at} is the location (default: the event's); {@code replace: air} refuses to
 * overwrite anything that is not air, which a {@code require} can then answer.
 *
 * <p><b>As the player</b> (the default) the player is asked first through the placement path ({@code BlockPlaceEvent}),
 * so a protection plugin that would stop them placing there stops the verb, and the step fails with "refused by a
 * listener". With the protection port (logic.md §43) the port is asked first in {@code protection_mode: both}, and alone in {@code
 * port}. {@code as_player: false} places with no actor: in {@code event} mode it asks nobody, otherwise it asks the port with no
 * actor; it works in a run with no player.
 *
 * <p><b>Budget.</b> One block is charged to the run's block budget, shared with {@code break_blocks}
 * ({@link WorldVerbs#MAX_BLOCKS_PER_RUN}, {@code MTR-0118}). A block outside the thread's region is placed on its own
 * region's thread, except as the player (the placement event must run beside the player), where it is refused
 * ({@code MTR-0119}); in {@code protection_mode: port} there is no event, so the block is reached on its own region.
 */
public final class SetBlockAction implements ActionType<SetBlockAction.Params> {

    /** Whether the verb overwrites any block or only air. */
    enum Replace { ANY, AIR }

    /** The parsed step. */
    public record Params(TemplateText block, LocationSpec at, boolean asPlayer, Replace replace) {}

    private final WorldVerbs verbs;

    /** @param verbs the platform: the region seam, the block seam, the custom-block runtime and the budget reporter */
    public SetBlockAction(WorldVerbs verbs) {
        this.verbs = verbs;
    }

    @Override
    public String id() {
        return "set_block";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Set block",
                "Replaces one block with a vanilla block or a Materione block, as the player by default so protection is asked",
                List.of(
                        new ParameterSchema("block", ParameterType.STRING, true, null,
                                "A vanilla block (stone, minecraft:oak_stairs[facing=north]) or a Materione block id",
                                null, null).acceptingExpression(),
                        new ParameterSchema("at", ParameterType.RAW, false, "event",
                                "Where: event, player, or {of, x, y, z} with a world-axis offset in blocks", null, null),
                        new ParameterSchema("as_player", ParameterType.BOOLEAN, false, "true",
                                "Ask the player's placement path so protection is asked; false asks nobody", null, null),
                        new ParameterSchema("replace", ParameterType.ENUM, false, "any",
                                "any overwrites whatever is there; air only fills an empty space",
                                List.of("any", "air"), null)),
                "2.0.0"), LogicCategory.WORLD, "block");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(params.template("block"),
                LocationSpec.read(params, "at", LocationSpec.Anchor.EVENT, false), params.bool("as_player", true),
                params.enumValue("replace", Replace.class, Replace.ANY));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return Stages.now(executeResumable(params, context, ReadExecutor.INLINE));
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        Player player = LocationSpec.runPlayer(context).orElse(null);
        if (params.asPlayer() && player == null) {
            return Stages.failed("there is no player to place as; write as_player: false for a run with none");
        }
        return params.block().renderResumable(context, reads).thenCompose(text ->
                params.at().resolveOffset(context, reads).thenCompose(at -> at.resolve(context, reads, verbs))
                        .thenCompose(where -> {
                            if (where.location() == null) {
                                return Stages.failed(where.problem());
                            }
                            if (!WorldVerbs.within(context, WorldVerbs.BLOCKS, 1, verbs.limits().blocksPerRun())) {
                                verbs.budgetSpent("set_block", verbs.limits().blocksPerRun(), "blocks");
                                return Stages.failed("this run has changed its " + verbs.limits().blocksPerRun()
                                        + " blocks");
                            }
                            Location location = where.location();
                            Object rule = context.facet(RunMeter.FACET).map(RunMeter::origin).orElse(null);
                            // the event path needs the player's thread beside the block; the port alone does not
                            // (protection_mode: port, protection.md section 5), so there the block is reached on its own region
                            if (params.asPlayer() && verbs.protection().runsEventPath()
                                    && !verbs.regions().ownedHere(location)) {
                                verbs.regionRefused("set_block", 1);
                                return Stages.failed("the block lies in a region this thread does not own");
                            }
                            return verbs.regions().at(location,
                                    () -> place(params, player, location.getBlock(), text.trim(), rule));
                        }));
    }

    @SuppressWarnings("try") // the scope is entered for its lifetime; nothing reads it
    private ActionOutcome place(Params params, Player player, Block block, String text, Object rule) {
        try (var scope = VerbScope.enter(rule)) {
            return placeWithin(params, player, block, text);
        }
    }

    private ActionOutcome placeWithin(Params params, Player player, Block block, String text) {
        if (params.replace() == Replace.AIR && !verbs.blocks().isAir(block)) {
            return ActionOutcome.failed("the block is occupied");
        }
        NamespacedId custom = customId(text);
        if (custom != null) {
            return placeCustom(params, player, block, custom);
        }
        BlockData data;
        try {
            data = verbs.blockData(text.contains(":") ? text : "minecraft:" + text.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ActionOutcome.failed("'" + text + "' is not a block");
        }
        ActionOutcome refusal = refusal(params, player, block, data);
        if (refusal != null) {
            return refusal;
        }
        return verbs.blocks().place(block, data) == BlockOps.Result.DONE
                ? ActionOutcome.OK : ActionOutcome.failed("the block could not be placed");
    }

    private ActionOutcome placeCustom(Params params, Player player, Block block, NamespacedId id) {
        Optional<CustomBlocks> blocks = verbs.customBlocks();
        if (blocks.isEmpty() || !blocks.get().exists(id)) {
            return ActionOutcome.failed("no Materione block '" + id + "' is loaded");
        }
        // the same question a vanilla placement asks; the host block's data stands in for the custom block's
        ActionOutcome refusal = refusal(params, player, block, block.getBlockData());
        if (refusal != null) {
            return refusal;
        }
        return blocks.get().place(block, id) ? ActionOutcome.OK : ActionOutcome.failed("the block could not be placed");
    }

    /**
     * Whether the placement may go on: null when it may, else the failed outcome that says why. The protection port is asked first
     * when the mode asks it (as the player, or with no actor for {@code as_player: false}), and then, for a player's placement in a
     * mode that runs the event path, the placement event (logic.md §43).
     */
    private ActionOutcome refusal(Params params, Player player, Block block, BlockData data) {
        var protection = verbs.protection();
        if (protection.asksPort()) {
            ProtectionActor actor = params.asPlayer() ? ProtectionActor.of(player) : ProtectionActor.none();
            ProtectionAnswer answer = protection.canPlace(actor, block.getLocation());
            if (answer.denied()) {
                return ActionOutcome.failed("the placement was " + answer.describeDenial());
            }
        }
        if (params.asPlayer() && protection.runsEventPath() && !verbs.blocks().mayPlace(player, block, data)) {
            return ActionOutcome.failed("the placement was refused by a listener");
        }
        return null;
    }

    /** The Materione block id {@code text} names, or null when it is a vanilla block. */
    static NamespacedId customId(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String namespace = text.substring(0, colon);
        if (namespace.equals("minecraft")) {
            return null;
        }
        try {
            return NamespacedId.parse(text);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
