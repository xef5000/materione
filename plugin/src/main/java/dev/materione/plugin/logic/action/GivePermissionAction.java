package dev.materione.plugin.logic.action;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.ProviderParams;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;

/**
 * {@code give_permission: example.fly}: grants a permission node to the run's player through the
 * active permission provider (LuckPerms, Vault, or the session default). Declared
 * {@link Threading#PLAYER_REGION}: the default provider attaches to the player, and the others are
 * safe to call from the player's own thread.
 */
public final class GivePermissionAction implements ActionType<GivePermissionAction.Params> {

    /** The parsed step. */
    public record Params(String node) {}

    private final LogicProviders providers;

    /** @param providers the provider slots, read on every run */
    public GivePermissionAction(LogicProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public String id() {
        return "give_permission";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Give permission",
                "Grants a permission node to the player through the active permission provider",
                List.of(new ParameterSchema("permission", ParameterType.STRING, true, null,
                        "Permission node, e.g. example.fly", null, null)),
                "2.0.0"), LogicCategory.SERVER, "permission");
    }

    @Override
    public Threading threading() {
        return Threading.PLAYER_REGION;
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(ProviderParams.node(params));
    }

    @Override
    public ActionOutcome execute(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return ActionOutcome.failed("event has no player");
        }
        ProviderResult result = providers.permissions().grant(player.getUniqueId(), params.node());
        return result.ok() ? ActionOutcome.OK : ActionOutcome.failed(result.reason());
    }
}
