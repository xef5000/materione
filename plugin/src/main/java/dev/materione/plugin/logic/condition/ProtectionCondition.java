package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.protection.ProtectionAction;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionQuery;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.world.LocationSpec;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/**
 * The questions content can ask the protection port itself (logic.md §43, protection.md section 7): {@code can_build},
 * {@code can_break} and {@code can_interact}. One class holds the shape, a subclass names the question, so the three are one
 * mechanism and a fourth is a three-line class.
 *
 * <pre>
 * - if: {can_build: {at: {of: player, y: 1}}}     # may this player build one block above their feet?
 * - if: {can_break: event}                         # in a break rule: may they break the block that was broken?
 * - if: {can_interact: {at: player, as_player: false}}   # may the world open things where they stand, by itself?
 * </pre>
 *
 * <p>{@code at} is the {@link LocationSpec} the world verbs read (default {@code player}); {@code as_player} (default true)
 * asks as the run's player, and {@code false} asks with no actor ("may the world do this by itself?"). The condition is true
 * unless a provider denies: with no provider present, or with every provider abstaining, it is true, because nothing is
 * protecting the place. It always asks the providers, whatever {@code protection_mode} says: the mode governs what a verb
 * asks on its own, and a condition is the author asking. It reads only, so a preview is the same call as a check.
 *
 * <p>A provider is asked on the thread that owns the location (the port hops there on Folia); a location that cannot be
 * read, or a run with no player when {@code as_player} is true, makes the condition false.
 */
public abstract class ProtectionCondition implements ConditionType<ProtectionCondition.Params> {

    /** The parsed condition: where, and whether the question is asked as the run's player. */
    public record Params(LocationSpec at, boolean asPlayer) {}

    private final String id;
    private final String name;
    private final String question;
    private final ProtectionAction action;
    private final WorldVerbs verbs;

    ProtectionCondition(String id, String name, String question, ProtectionAction action, WorldVerbs verbs) {
        this.id = id;
        this.name = name;
        this.question = question;
        this.action = action;
        this.verbs = verbs;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription(name,
                "True unless a protection provider (WorldGuard, a claim plugin, an addon's own) says " + question
                        + ". True when nothing protects the place. Asks the providers whatever protection_mode says",
                List.of(
                        new ParameterSchema("at", ParameterType.RAW, false, "player",
                                "Where: event (the location of the trigger), player (default), or {of, x, y, z} with a "
                                        + "world-axis offset in blocks", null, null),
                        new ParameterSchema("as_player", ParameterType.BOOLEAN, false, "true",
                                "Ask as the run's player; false asks with no actor (may the world do this by itself?)",
                                null, null)),
                "2.0.0"), LogicCategory.WORLD, "at");
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(LocationSpec.read(params, "at", LocationSpec.Anchor.PLAYER, false),
                params.bool("as_player", true));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        CompletableFuture<Boolean> answer = testResumable(params, context, ReadExecutor.INLINE).toCompletableFuture();
        return answer.isDone() && !answer.isCompletedExceptionally() && answer.getNow(false);
    }

    @Override
    public CompletionStage<Boolean> testResumable(Params params, LogicContext context, ReadExecutor reads) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (params.asPlayer() && player == null) {
            return CompletableFuture.completedFuture(false);
        }
        ProtectionActor actor = params.asPlayer() ? ProtectionActor.of(player) : ProtectionActor.none();
        return params.at().resolveOffset(context, reads).thenCompose(at -> at.resolve(context, reads, verbs))
                .thenCompose(where -> {
                    if (where.location() == null) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return verbs.protection().askAt(ProtectionQuery.of(action, actor, where.location()))
                            .thenApply(answer -> !answer.denied());
                });
    }
}
