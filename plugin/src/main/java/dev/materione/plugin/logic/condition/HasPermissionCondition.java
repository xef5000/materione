package dev.materione.plugin.logic.condition;

import dev.materione.api.logic.ConditionDescription;
import dev.materione.api.logic.ConditionType;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.ProviderParams;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * {@code has_permission: example.fly}, {@code has_permission: [a.b, c.d]} or
 * {@code has_permission: {permissions: [a.b, c.d], min: 1}} (logic.md §22).
 *
 * <p>With several nodes every one must hold, unless {@code min} says how many are enough. The check is
 * {@link Player#hasPermission}, which the active permission provider feeds: the session attachments the
 * default {@code give_permission} writes, and LuckPerms through its injected permissible. A Vault
 * permission service that is not mirrored into Bukkit is not consulted here (logic.md §22, decision 3).
 */
public final class HasPermissionCondition implements ConditionType<HasPermissionCondition.Params> {

    /** The nodes to test and how many must hold. */
    public record Params(List<String> nodes, int min) {}

    @Override
    public String id() {
        return "has_permission";
    }

    @Override
    public ConditionDescription describe() {
        return new ConditionDescription(new SelfDescription("Has permission",
                "True when the player holds the permission node (or at least min of several)",
                List.of(
                        new ParameterSchema("permission", ParameterType.STRING, false, null,
                                "One node, e.g. example.fly; a list of nodes is accepted here too", null, null),
                        new ParameterSchema("permissions", ParameterType.RAW, false, null,
                                "A list of nodes; every one must hold unless min is set", null, null),
                        new ParameterSchema("min", ParameterType.INTEGER, false, null,
                                "How many of permissions must hold (default: all)", null, null)),
                "2.0.0"), LogicCategory.PLAYER, "permission");
    }

    @Override
    public Params parse(ParamReader params) {
        boolean single = params.raw("permission").isPresent();
        boolean many = params.raw("permissions").isPresent();
        if (single == many) {
            throw new IllegalArgumentException(single
                    ? "give permission or permissions, not both"
                    : "permission is required (or permissions: [ ... ])");
        }
        var nodes = new ArrayList<String>();
        if (single && params.raw("permission").orElseThrow() instanceof String text) {
            nodes.add(ProviderParams.checkNode(text.trim()));
        } else {
            for (String node : params.strings(single ? "permission" : "permissions")) {
                nodes.add(ProviderParams.checkNode(node.trim()));
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("permissions needs at least one node");
        }
        int min = params.integer("min", nodes.size(), 0, nodes.size());
        return new Params(List.copyOf(nodes), min);
    }

    @Override
    public boolean test(Params params, LogicContext context) {
        Player player = context.facet(Facets.PLAYER).orElse(null);
        if (player == null) {
            return false;
        }
        int held = 0;
        for (String node : params.nodes()) {
            if (player.hasPermission(node)) {
                held++;
            }
        }
        return held >= params.min();
    }
}
