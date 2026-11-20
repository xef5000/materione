package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.Subjects;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.platform.text.MaterioneText;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/**
 * {@code message: "&aHi"} or {@code message: {text, target}}: shows text to the player the rule runs for, or to the
 * {@code target} (another player, the struck entity if it is a player, the players nearby; logic.md §39), in
 * Materione rich text. The action bar and the whole server have their own actions ({@code actionbar},
 * {@code broadcast}); {@code message} used to carry a channel under the name {@code target}, which is now the
 * address every targetable action shares.
 */
public final class MessageAction implements ActionType<MessageAction.Params> {

    /** The parsed step. */
    public record Params(TemplateText text) {}

    @Override
    public String id() {
        return "message";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Send message",
                "Shows text to the player, or to another player given as a target",
                List.of(
                        new ParameterSchema("text", ParameterType.STRING, true, null,
                                "Message text; formatting and & colour codes allowed", null, null).acceptingExpression()),
                "2.0.0"), LogicCategory.PLAYER, "text").withTargets(TargetKind.PLAYER);
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        params.raw("target").filter(value -> value instanceof String word
                && List.of("player", "actionbar", "broadcast").contains(word)).ifPresent(word -> {
            throw new IllegalArgumentException("target '" + word + "' was a message channel; message now takes a "
                    + "target (self, event.target, arg, player, nearby), and the action bar and the whole server "
                    + "are the actions actionbar and broadcast");
        });
        return new Params(params.template("text"));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        return send(params.text().render(context), context);
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(
            Params params, LogicContext context, ReadExecutor executor) {
        return params.text().renderResumable(context, executor)
                .thenCompose(text -> executor.on(threading(params), () -> send(text, context)));
    }

    private static ActionOutcome send(String text, LogicContext context) {
        Player player = Subjects.player(context).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        player.sendMessage(MaterioneText.parse(text));
        return ActionOutcome.OK;
    }
}
