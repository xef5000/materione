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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code run_command: "say hi"} or {@code run_command: {command, as: console|player|op}}: runs a
 * server command, with {@code ${...}} and {@code %placeholders%} filled in.
 *
 * <p>Declared {@link Threading#GLOBAL} because commands may touch any region and the default
 * sender is the console. The M2 engine runs every GLOBAL action one scheduler hop later on the
 * player's thread; the flow interpreter must route the player and op senders to the player's
 * region when it dispatches for real (logic.md §4.1).
 */
public final class RunCommandAction implements ActionType<RunCommandAction.Params> {

    /** Who runs the command. */
    public enum Sender { CONSOLE, PLAYER, OP }

    /** The parsed step. */
    public record Params(TemplateText command, Sender sender) {}

    @Override
    public String id() {
        return "run_command";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Run command",
                "Runs a server command; %player% and ${...} are filled in",
                List.of(
                        new ParameterSchema("command", ParameterType.STRING, true, null,
                                "Command without the leading slash", null, null),
                        new ParameterSchema("as", ParameterType.ENUM, false, "console", "Who runs it",
                                List.of("console", "player", "op"), null)),
                "2.0.0"), LogicCategory.SERVER, "command");
    }

    @Override
    public Threading threading() {
        return Threading.GLOBAL;
    }

    @Override
    public Params parse(ParamReader params) {
        Sender sender = Sender.valueOf(params.choice("as", "console", List.of("console", "player", "op"))
                .toUpperCase(java.util.Locale.ROOT));
        return new Params(params.template("command"), sender);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        String rendered = params.command().render(context);
        String command = rendered.startsWith("/") ? rendered.substring(1) : rendered;
        if (params.sender() == Sender.CONSOLE) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return ActionOutcome.OK;
        }
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        if (params.sender() == Sender.PLAYER) {
            return player.performCommand(command)
                    ? ActionOutcome.OK
                    : ActionOutcome.failed("player has no command '" + rendered + "'");
        }
        boolean previousOp = player.isOp();
        try {
            player.setOp(true);
            player.performCommand(command);
        } finally {
            player.setOp(previousOp);
        }
        return ActionOutcome.OK;
    }
}
