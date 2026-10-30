package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.platform.text.MaterioneText;
import java.util.List;
import org.bukkit.entity.Player;

/** {@code actionbar: "&7Mined a block"}: shows text over the player's hotbar. */
public final class ActionbarAction implements ActionType<ActionbarAction.Params> {

    /** The parsed step. */
    public record Params(TemplateText text) {}

    @Override
    public String id() {
        return "actionbar";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Action bar",
                "Shows text above the player's hotbar",
                List.of(new ParameterSchema("text", ParameterType.STRING, true, null,
                        "Message text; formatting and & colour codes allowed", null, null)),
                "2.0.0"), LogicCategory.PLAYER, "text");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(params.template("text"));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        player.sendActionBar(MaterioneText.parse(params.text().render(context)));
        return ActionOutcome.OK;
    }
}
