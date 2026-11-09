package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.gui.TextureSource;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.behavior.logic.RegistryLogicLookup;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.core.registry.RegistryService;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.plugin.gui.open.OpenPlan;
import dev.materione.plugin.gui.prompt.PromptRuntime;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import dev.materione.plugin.logic.BuiltinLogic;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The showcase pack (#227, logic.md §34): every GUI file of {@code fixtures/packs/showcase} loads through the real GUI
 * compiler, the real rule compiler and the real open-block compiler against the built-in registries, with zero
 * diagnostics and no skipped layer or step. The pack's declared variables are declared here, as its variables files do
 * at boot, so a write to {@code showcase:stock} or {@code showcase:show_tips} is checked as a declared write. The
 * open targets of the shop (the servers, the players, the settings) and the currency are resolved at run time, and the
 * in-game QA catalog (qa/catalog/showcase.mjs) exercises them against the real pack.
 */
class ShowcasePackTest {

    static final List<String> GUIS = List.of("shop", "servers", "players", "settings", "confirm", "hopper", "dispenser");

    private static final Map<NamespacedId, VariableDeclaration> DECLARED = Map.of(
            NamespacedId.of("showcase", "show_tips"),
            new VariableDeclaration(NamespacedId.of("showcase", "show_tips"), VariableScope.PLAYER,
                    VariableType.BOOLEAN, false, "Shows the tip line in the showcase shop"),
            NamespacedId.of("showcase", "stock"),
            new VariableDeclaration(NamespacedId.of("showcase", "stock"), VariableScope.SERVER,
                    VariableType.NUMBER, 3, "Emeralds left in the showcase shop"));

    @Test
    void everyShowcaseGuiLoadsWithoutDiagnostics() throws IOException {
        // the plugin's own built-ins (prompt, gui.* and the rest), as the live server registers them at boot
        var registries = new RegistryService();
        var service = LogicTestSupport.newService(registries);
        var host = LogicTestSupport.owner("materione");
        BuiltinLogic.register(service, host, dev.materione.plugin.logic.support.ItemCatalog.none(), new GuiBridge());
        BuiltinLogic.registerPrompts(service, host, PromptRuntime.create(GuiFixture.plugin(), unusedScheduling(),
                new GuiBridge(), diagnostic -> { }));
        var lookup = new RegistryLogicLookup(registries, id -> Optional.ofNullable(DECLARED.get(id)));

        for (String name : GUIS) {
            var text = Files.readString(showcase().resolve("guis").resolve(name + ".yml"));
            var fixture = new GuiFixture();
            var definition = fixture.definition(text);
            var owner = NamespacedId.of("showcase", name);

            assertThat(definition.skipped()).as(name + " skipped layers").isEmpty();
            assertThat(definition.rules().problems()).as(name + " rule problems").isEmpty();

            // dynamic layers (visible_if, templated items) need overlay glyph allocations, as the live compile supplies them
            var overlays = new AtomicInteger(0xE201);
            var compiled = GuiCompiler.compile(definition, 0xE200, TextureSource.NONE,
                    (layer, frame) -> overlays.getAndIncrement());
            assertThat(compiled.diagnostics()).as(name + " layer diagnostics").isEmpty();

            var rules = GuiRuleSet.compile(owner, definition.rules(), lookup);
            assertThat(rules.problems()).as(name + " rule diagnostics").isEmpty();

            var plan = OpenPlan.compile(owner, definition.rules(), lookup);
            if (name.equals("shop")) {
                assertThat(plan).as("shop has an open block").isNotNull();
                assertThat(plan.commands()).containsExactly("shop");
                assertThat(plan.problems()).as("shop open diagnostics").isEmpty();
            } else {
                assertThat(plan).as(name + " has no open block").isNull();
            }
        }
    }

    @Test
    void theShowcaseHoldsExactlyTheSevenGuisAndTheDeclarations() throws IOException {
        try (Stream<Path> files = Files.list(showcase().resolve("guis"))) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted())
                    .containsExactlyElementsOf(GUIS.stream().sorted().map(name -> name + ".yml").toList());
        }
        assertThat(Files.readString(showcase().resolve("variables").resolve("showcase.yml")))
                .contains("show_tips:", "scope: player", "stock:", "scope: server");
        assertThat(Files.readString(showcase().resolve("currencies").resolve("points.yml")))
                .contains("integration: xp");
    }

    private static Path showcase() {
        return GoldenFiles.fixturesRoot().resolve("packs").resolve("showcase");
    }

    /** Only the prompt's constructor reads it; these tests compile rules and never run a prompt. */
    private static SchedulingGateway unusedScheduling() {
        return (SchedulingGateway) Proxy.newProxyInstance(SchedulingGateway.class.getClassLoader(),
                new Class<?>[] {SchedulingGateway.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("scheduling." + method.getName());
                });
    }
}
