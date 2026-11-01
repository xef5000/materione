package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.ExperienceUnit;
import dev.materione.plugin.logic.support.Quantity;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/**
 * {@code has_exp: 50} or {@code has_exp: {amount, unit: points|levels}} (logic.md §22). It is the read
 * half of {@code take_exp} and counts the same way: points are the player's total experience, levels are
 * the level. An amount the template does not render to a whole number in range is false.
 */
public final class HasExpCondition implements ConditionType<HasExpCondition.Params> {

    /** The amount to hold and the unit it is counted in. */
    public record Params(Quantity amount, ExperienceUnit unit) {}

    @Override
    public String id() {
        return "has_exp";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("Has experience",
                "True when the player holds at least the amount of experience points or levels",
                List.of(
                        new ParameterSchema("amount", ParameterType.INTEGER, true, null,
                                "How many points or levels, from 1", null, null),
                        new ParameterSchema("unit", ParameterType.ENUM, false, "points",
                                "What the amount counts", List.of("points", "levels"), null)),
                "2.0.0"), LogicCategory.PLAYER, "amount");
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(
                Quantity.requiredCount(params, "amount", 1, ExperienceUnit.MAX_AMOUNT),
                params.enumValue("unit", ExperienceUnit.class, ExperienceUnit.POINTS));
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        OptionalInt whole = params.amount().wholeValue(context);
        if (whole.isEmpty()) {
            return false;
        }
        int need = whole.getAsInt();
        return params.unit() == ExperienceUnit.LEVELS
                ? player.getLevel() >= need
                : player.getTotalExperience() >= need;
    }

    @Override public CompletionStage<Boolean> testResumable(Params params, LogicContext context, ReadExecutor reads) {
        if (context.facet(Facets.PLAYER).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return params.amount().resolveResumable(context, reads)
                .thenCompose(amount -> reads.on(Threading.PLAYER_REGION,
                        () -> test(new Params(amount, params.unit()), context)));
    }
}
