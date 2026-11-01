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
import dev.materione.plugin.logic.support.ResolvedText;
import dev.materione.platform.text.MaterioneText;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code broadcast: "&6Boss down!"} or {@code broadcast: {text, scope: server|world|permission,
 * target}}: sends a message to everyone on the server, to the players of one world, or to the
 * players holding a permission node. {@code target} names the world (default: the event player's
 * world) or the permission (required for {@code scope: permission}).
 */
public final class BroadcastAction implements ActionType<BroadcastAction.Params> {

    /** Who receives the message. */
    public enum Scope { SERVER, WORLD, PERMISSION }

    /** The parsed step. */
    public record Params(TemplateText text, Scope scope, Optional<TemplateText> target) {}

    @Override
    public String id() {
        return "broadcast";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Broadcast",
                "Sends a message to the whole server, one world, or the holders of a permission",
                List.of(
                        new ParameterSchema("text", ParameterType.STRING, true, null,
                                "Message text; formatting and & colour codes allowed", null, null),
                        new ParameterSchema("scope", ParameterType.ENUM, false, "server",
                                "Who receives it", List.of("server", "world", "permission"), null),
                        new ParameterSchema("target", ParameterType.STRING, false, null,
                                "World name (world scope; default the player's world) or permission node "
                                        + "(permission scope, required)", null, null)),
                "2.0.0"), LogicCategory.SERVER, "text");
    }

    @Override
    public Threading threading() {
        return Threading.GLOBAL;
    }

    @Override
    public Params parse(ParamReader params) {
        Scope scope = params.enumValue("scope", Scope.class, Scope.SERVER);
        var target = params.optionalTemplate("target");
        if (scope == Scope.PERMISSION && target.isEmpty()) {
            throw new IllegalArgumentException("target is required for scope permission");
        }
        return new Params(params.template("text"), scope, target);
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        var message = MaterioneText.parse(params.text().render(context));
        Player player = context.facet(Facets.PLAYER).orElse(null);
        switch (params.scope()) {
            case SERVER -> Bukkit.broadcast(message);
            case PERMISSION -> {
                String permission = params.target().orElseThrow().render(context).trim();
                if (permission.isEmpty()) {
                    return ActionOutcome.failed("the permission node is empty");
                }
                Bukkit.broadcast(message, permission);
            }
            case WORLD -> {
                World world;
                if (params.target().isPresent()) {
                    String name = params.target().get().render(context).trim();
                    world = Bukkit.getWorld(name);
                    if (world == null) {
                        return ActionOutcome.failed("no world named '" + name + "'");
                    }
                } else {
                    if (player == null) {
                        return ActionOutcome.failed("event has no player");
                    }
                    world = player.getWorld();
                }
                world.getPlayers().forEach(recipient -> recipient.sendMessage(message));
            }

        }
        return ActionOutcome.OK;
    }

    @Override
    public CompletionStage<ActionOutcome> executeResumable(Params params, LogicContext context, ReadExecutor reads) {
        return ResolvedText.resolve(params.text(), context, reads).thenCompose(text -> {
            CompletionStage<Optional<TemplateText>> target = params.scope() == Scope.SERVER
                    ? CompletableFuture.completedFuture(params.target())
                    : ResolvedText.resolve(params.target(), context, reads);
            return target.thenCompose(resolved -> reads.on(threading(),
                    () -> execute(new Params(text, params.scope(), resolved), context)));
        });
    }
}
