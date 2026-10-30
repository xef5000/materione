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
import java.util.List;
import org.bukkit.entity.Player;

/**
 * {@code chat: "I found a chest!"} or {@code chat: "/spawn"}: makes the player say something in
 * chat, or run a command as themselves. A leading slash means a command, as in the chat box.
 */
public final class ChatAction implements ActionType<ChatAction.Params> {

    /** The parsed step. */
    public record Params(TemplateText text) {}

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Chat as player",
                "Makes the player say a chat message, or run a command as themselves",
                List.of(new ParameterSchema("text", ParameterType.STRING, true, null,
                        "Chat text, or a command with a leading slash", null, null)),
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
        String rendered = params.text().render(context).trim();
        if (rendered.isEmpty()) {
            return ActionOutcome.failed("the chat text is empty");
        }
        if (rendered.startsWith("/")) {
            String command = rendered.substring(1);
            return player.performCommand(command)
                    ? ActionOutcome.OK
                    : ActionOutcome.failed("player has no command '" + command + "'");
        }
        player.chat(rendered);
        return ActionOutcome.OK;
    }
}
