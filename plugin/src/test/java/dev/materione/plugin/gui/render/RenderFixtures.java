package dev.materione.plugin.gui.render;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.content.gui.ItemComponent;
import dev.materione.content.gui.logic.ClickMap;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.dynamic.CompiledItemLayer;
import dev.materione.plugin.gui.dynamic.ItemBinding;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import dev.materione.plugin.logic.LogicTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;

/** Builders shared by the render tests: compiled layers, live GUIs and viewer contexts, without a server. */
final class RenderFixtures {

    static final NamespacedId GUI = NamespacedId.of("shop", "main");
    static final Set<String> ROOTS = Set.of("player", "server", "vars", "gui");

    private RenderFixtures() {}

    /** The logic registries with the built-ins, so conditions and {@code materione:item} templates compile. */
    static dev.materione.behavior.logic.LogicLookup lookup() {
        return LogicTestSupport.withBuiltIns(new RegistryService()).lookup();
    }

    static Condition condition(String expression) {
        return FlowCompiler.compileCondition(GUI, "test", expression, ROOTS, lookup()).condition().orElseThrow();
    }

    static dev.materione.api.logic.TemplateText template(String text) {
        return EngineExpressions.INSTANCE.template(text);
    }

    /** A viewer at {@code level}, with a name and no run variables. */
    static LogicContext viewer(int level) {
        var player = new LinkedHashMap<String, Object>();
        player.put("level", level);
        player.put("name", "Probe");
        var root = new LinkedHashMap<String, Object>();
        root.put("player", player);
        root.put("vars", new LinkedHashMap<String, Object>());
        root.put("gui", new LinkedHashMap<String, Object>());
        root.put("server", new LinkedHashMap<String, Object>());
        return new MapLogicContext(root);
    }

    /** A source that cannot produce an item, with the reason it shows (a pure appearance, no server stack). */
    static ItemBinding barrier(String reason) {
        return context -> ItemResolution.unresolved(reason);
    }

    /** A source with nothing to show. */
    static ItemBinding nothing() {
        return context -> ItemResolution.none();
    }

    /** A plain item layer: no condition, no frames, the template amount 1. */
    static CompiledItemLayer layer(String id, int order, int slot, ItemBinding source) {
        return new CompiledItemLayer(id, order, slot, null, source, null, 0, null, List.of(),
                ItemComponent.LoreMode.REPLACE, template("1"), null, false);
    }

    /** A layer with a condition, which makes it dynamic. */
    static CompiledItemLayer conditional(String id, int order, int slot, String when, ItemBinding source) {
        return new CompiledItemLayer(id, order, slot, condition(when), source, null, 0, null, List.of(),
                ItemComponent.LoreMode.REPLACE, template("1"), null, true);
    }

    /** A live GUI over {@code items} (in layer order) and the given static slots. */
    static RuntimeGuis.Live gui(List<RuntimeGuis.Slot> slots, List<CompiledItemLayer> items) {
        return new RuntimeGuis.Live(GUI, 3, "Shop", "Shop", slots, ClickMap.EMPTY, GuiRuleSet.EMPTY, List.of(), 0,
                items);
    }

    static RuntimeGuis.Live gui(List<CompiledItemLayer> items) {
        return gui(List.of(), items);
    }

    /** A static slot showing a vanilla stone, which needs no server to build. */
    static RuntimeGuis.Slot stoneAt(int slot) {
        return new RuntimeGuis.Slot(slot, new RuntimeGuis.Item.Vanilla(Material.STONE), 1);
    }

    /** Records every problem a render reports. */
    static final class Reports implements RenderReporter {
        final List<String> codes = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public void problem(dev.materione.api.diagnostics.DiagnosticCode code, NamespacedId gui, String layerId,
                String message) {
            codes.add(code.code());
            messages.add(layerId + ": " + message);
        }
    }

}
