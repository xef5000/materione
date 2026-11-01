package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.platform.text.MaterioneText;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code message: "&aHi"} or {@code message: {text, target: player|actionbar|broadcast}}: shows
 * text to the player, on the action bar, or to everyone, in Materione rich text.
 */
public final class MessageAction implements ActionType<MessageAction.Params> {

    /** Where the text appears. */
    public enum Target { PLAYER, ACTIONBAR, BROADCAST }

    /** The parsed step. */
    public record Params(TemplateText text, Target target) {}

    @Override
    public String id() {
        return "message";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Send message",
                "Shows text to the player, on the action bar, or to everyone",
                List.of(
                        new ParameterSchema("text", ParameterType.STRING, true, null,
                                "Message text; formatting and & colour codes allowed", null, null),
                        new ParameterSchema("target", ParameterType.ENUM, false, "player", "Where the text appears",
                                List.of("player", "actionbar", "broadcast"), null)),
                "2.0.0"), LogicCategory.PLAYER, "text");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(params.template("text"), params.enumValue("target", Target.class, Target.PLAYER));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return send(params.target(), params.text().render(context), context);
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(
            Params params, LogicContext context, ReadExecutor executor) {
        return params.text().renderResumable(context, executor)
                .thenCompose(text -> executor.on(threading(params), () -> send(params.target(), text, context)));
    }

    private static ActionOutcome send(Target target, String text, LogicContext context) {
        var message = MaterioneText.parse(text);
        if (target == Target.BROADCAST) {
            Bukkit.broadcast(message);
            return ActionOutcome.OK;
        }
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        if (target == Target.ACTIONBAR) {
            player.sendActionBar(message);
        } else {
            player.sendMessage(message);
        }
        return ActionOutcome.OK;
    }
}
