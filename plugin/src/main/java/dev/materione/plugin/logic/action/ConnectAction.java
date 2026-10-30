package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.logic.provider.ServerTransfer;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * {@code connect: lobby}: sends the run's player to another server through the active
 * {@link ServerTransfer}. A proxy uses the name as the target server; the transfer packet goes to
 * the host configured in settings and ignores it. Unavailable, with a reason, when no transfer is
 * configured. {@link Threading#PLAYER_REGION}: the transfer acts on the player.
 */
public final class ConnectAction implements ActionType<ConnectAction.Params> {

    /** The parsed step. */
    public record Params(String server) {}

    private final LogicProviders providers;

    /** @param providers the provider slots, read on every run and availability check */
    public ConnectAction(LogicProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public String id() {
        return "connect";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Connect to server",
                "Sends the player to another server; a proxy takes the name, the transfer packet uses its configured host",
                List.of(new ParameterSchema("server", ParameterType.STRING, true, null,
                        "Target server name as the proxy knows it", null, null)),
                "2.0.0"), LogicCategory.SERVER, "server");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(params.text("server"));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        ServerTransfer transfer = providers.transfer().filter(ServerTransfer::available).orElse(null);
        if (transfer == null) {
            return ActionOutcome.failed("no server transfer");
        }
        ProviderResult result = transfer.send(player.getUniqueId(), params.server());
        return result.ok() ? ActionOutcome.OK : ActionOutcome.failed(result.reason());
    }

    @Override
    public Availability availability() {
        return providers.transfer().filter(ServerTransfer::available).isPresent()
                ? Availability.AVAILABLE
                : Availability.unavailable("No server transfer is configured (set transfer_host, or install a proxy channel)");
    }
}
