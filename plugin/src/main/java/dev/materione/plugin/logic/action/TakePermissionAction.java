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
 * {@code take_permission: example.fly}: removes a permission node from the run's player through the
 * active permission provider. The session default removes only the grant it made; a node a
 * permission plugin gives stays until that plugin removes it. {@link Threading#PLAYER_REGION}, as
 * {@code give_permission}.
 */
public final class TakePermissionAction implements ActionType<TakePermissionAction.Params> {

    /** The parsed step. */
    public record Params(String node) {}

    private final LogicProviders providers;

    /** @param providers the provider slots, read on every run */
    public TakePermissionAction(LogicProviders providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public String id() {
        return "take_permission";
    }

    @Override
    public ActionDescription describe() {
        return new ActionDescription(new SelfDescription("Take permission",
                "Removes a permission node from the player through the active permission provider",
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
        ProviderResult result = providers.permissions().revoke(player.getUniqueId(), params.node());
        return result.ok() ? ActionOutcome.OK : ActionOutcome.failed(result.reason());
    }
}
