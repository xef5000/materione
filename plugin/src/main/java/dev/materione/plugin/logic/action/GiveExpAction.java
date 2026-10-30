package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.support.ExperienceUnit;
import dev.materione.plugin.logic.support.Quantity;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * {@code give_exp: 50} or {@code give_exp: {amount, unit: points|levels}}: grants experience. The
 * unit is total points by default; {@code levels} grants whole levels.
 */
public final class GiveExpAction implements ActionType<GiveExpAction.Params> {

    /** The parsed step. */
    public record Params(Quantity amount, ExperienceUnit unit) {}

    @Override
    public String id() {
        return "give_exp";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Give experience",
                "Grants the player experience points or levels",
                List.of(
                        new ParameterSchema("amount", ParameterType.INTEGER, true, null,
                                "How many points or levels", null, null),
                        new ParameterSchema("unit", ParameterType.ENUM, false, "points",
                                "What the amount counts", List.of("points", "levels"), null)),
                "2.0.0"), LogicCategory.PLAYER, "amount");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(
                Quantity.requiredCount(params, "amount", 1, ExperienceUnit.MAX_AMOUNT),
                params.enumValue("unit", ExperienceUnit.class, ExperienceUnit.POINTS));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        var whole = params.amount().wholeValue(context);
        if (whole.isEmpty() || whole.getAsInt() < 1 || whole.getAsInt() > ExperienceUnit.MAX_AMOUNT) {
            return ActionOutcome.failed("amount must be a whole number from 1 to " + ExperienceUnit.MAX_AMOUNT);
        }
        if (params.unit() == ExperienceUnit.LEVELS) {
            player.giveExpLevels(whole.getAsInt());
        } else {
            player.giveExp(whole.getAsInt());
        }
        return ActionOutcome.OK;
    }
}
