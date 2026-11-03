package dev.materione.plugin.gui.list;

import dev.materione.api.logic.ContextVariable;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ListSource;
import dev.materione.api.logic.ListSourceDescription;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code online_players}: every online player, as {@code name} and {@code uuid}, in the server's order. Only the
 * name and the UUID are read: a player's other state belongs to that player's thread, which a list on another
 * viewer's thread must not touch (logic.md §6.7). The viewer can be left out with {@code exclude_viewer: true}.
 */
public final class OnlinePlayersSource implements ListSource<OnlinePlayersSource.Params> {

    /** The parsed parameters. */
    public record Params(boolean excludeViewer) {}

    @Override
    public String id() {
        return "online_players";
    }

    @Override
    public ListSourceDescription describe() {
        return ListSourceDescription.of(
                new SelfDescription("Online players", "Every online player, by name and UUID",
                        List.of(new ParameterSchema("exclude_viewer", ParameterType.BOOLEAN, false, "false",
                                "Leave the viewer out of the list", null, null)),
                        "1.0.0"),
                List.of(
                        ContextVariable.of("entry.name", ParameterType.STRING, "The player's name"),
                        ContextVariable.of("entry.uuid", ParameterType.STRING, "The player's UUID")));
    }

    @Override
    public Params parse(ParamReader params) {
        return new Params(params.bool("exclude_viewer", false));
    }

    @Override
    public List<Map<String, Object>> entries(Params parameters, LogicContext context) {
        UUID viewer = parameters.excludeViewer()
                ? context.facet(Facets.PLAYER).map(Player::getUniqueId).orElse(null)
                : null;
        var entries = new ArrayList<Map<String, Object>>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(viewer)) {
                continue;
            }
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", player.getName());
            entry.put("uuid", player.getUniqueId().toString());
            entries.add(entry);
        }
        return entries;
    }
}
