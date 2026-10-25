package dev.materione.plugin.runtime.mechanics.builtin;

import dev.materione.api.NamespacedId;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Click commands are a canned bundle in the shared ECA vocabulary (D4). */
public final class CommandsMechanic implements Mechanic {

    public record Instance(
            NamespacedId itemId, List<String> onRightClick, List<String> onLeftClick, boolean asPlayer)
            implements MechanicInstance {
        public Instance {
            onRightClick = List.copyOf(onRightClick);
            onLeftClick = List.copyOf(onLeftClick);
        }

        @Override
        public List<Map<String, Object>> behaviorRules() {
            var rules = new ArrayList<Map<String, Object>>();
            addRule(rules, "right", onRightClick);
            addRule(rules, "left", onLeftClick);
            return List.copyOf(rules);
        }

        private void addRule(List<Map<String, Object>> rules, String side, List<String> commands) {
            if (commands.isEmpty()) {
                return;
            }
            List<Map<String, Object>> actions = commands.stream()
                    .<Map<String, Object>>map(command -> Map.of(
                            "run_command", Map.of(
                                    "command", command.replace("%player%", "${player.name}"),
                                    "as", asPlayer ? "player" : "console")))
                    .toList();
            rules.add(Map.of(
                    "id", itemId + "#mechanic:materione:commands/" + side,
                    "on", "click:" + side,
                    "do", actions));
        }
    }

    @Override
    public String name() {
        return "commands";
    }

    @Override
    public boolean repeatable() {
        return true;
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Commands",
                "Run commands when the item is clicked",
                List.of(
                        new ParameterSchema("on_right_click", ParameterType.LIST, false, null,
                                "Commands on right-click (%player% substituted)", null, null),
                        new ParameterSchema("on_left_click", ParameterType.LIST, false, null,
                                "Commands on left-click", null, null),
                        new ParameterSchema("as_player", ParameterType.BOOLEAN, false, "false",
                                "Run as the clicking player instead of the console", null, null)),
                "2.0.0");
    }

    @Override
    public Instance parse(MechanicConfiguration configuration) {
        return new Instance(
                configuration.itemId(), configuration.strings("on_right_click"),
                configuration.strings("on_left_click"), configuration.bool("as_player", false));
    }
}
