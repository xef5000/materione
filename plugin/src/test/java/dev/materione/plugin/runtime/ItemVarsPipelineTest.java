package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PackManifest;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.core.runtime.RuntimeVariables;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.plugin.runtime.behavior.ItemBehaviors;
import dev.materione.plugin.runtime.behavior.WorldRules;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Item-scoped variables from files to the live shadow (logic.md section 38, D6: one pipeline): the {@code itemvars}
 * fixture pack declares them with {@code scope: item}, an item and a rule document read and write them with no
 * diagnostics at all, and a trigger that is not about an item stack cannot name one (MTR-0060 at load, offline).
 */
class ItemVarsPipelineTest {

    private static final Path PACK = GoldenFiles.fixturesRoot().resolve("packs").resolve("itemvars");

    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final PipelineService pipeline = new PipelineService(null, diagnostics, new QuarantineTracker(),
            registries, null, new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    ItemVarsPipelineTest() {
        LogicTestSupport.withBuiltIns(registries, List.of("zombie", "player"));
        pipeline.registerBuiltins();
    }

    private static ContentResource file(Path file, String type, String id) throws IOException {
        return text(Files.readString(file), file, type, id);
    }

    private static ContentResource text(String yaml, Path file, String type, String id) {
        return new ContentResource(NamespacedId.of("itemvars", id), type, file, LosslessYaml.parse(yaml));
    }

    private RuntimeRegistries shadow(List<ContentResource> resources) {
        var manifest = new PackManifest("itemvars", "1", 1, List.of(), LosslessYaml.parse("id: itemvars\n"));
        return pipeline.buildShadow(List.of(new ContentPack("itemvars", Path.of("itemvars"), manifest, resources)));
    }

    private List<ContentResource> fixturePack() throws IOException {
        return List.of(
                file(PACK.resolve("variables/sword.yml"), "variable", "level"),
                file(PACK.resolve("variables/sword.yml"), "variable", "title"),
                file(PACK.resolve("items/trained_sword.yml"), "item", "trained_sword"),
                file(PACK.resolve("rules/arena.yml"), "rule", "arena"));
    }

    @Test
    void theFixturePackDeclaresItemVariablesAndItsRulesLoadWithNoDiagnostics() throws IOException {
        RuntimeRegistries shadow = shadow(fixturePack());

        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message).isEmpty();
        RuntimeVariables declared = shadow.view(RuntimeVariables.KEY);
        var level = declared.find(NamespacedId.of("itemvars", "level")).orElseThrow();
        assertThat(level.scope()).isEqualTo(VariableScope.ITEM);
        assertThat(level.defaultValue()).isEqualTo(1.0);
        assertThat(declared.find(NamespacedId.of("itemvars", "title")).orElseThrow().scope())
                .isEqualTo(VariableScope.ITEM);
        // the item and the rule document both loaded their rules: neither was skipped for naming a stack
        var sword = shadow.view(ItemBehaviors.KEY).forItem(NamespacedId.of("itemvars", "trained_sword")).orElseThrow();
        assertThat(sword).extracting(rule -> rule.id()).containsExactly("train", "earn_title");
        assertThat(shadow.view(WorldRules.KEY).forTrigger(EventTypes.ENTITY_KILL)).hasSize(1);
    }

    @Test
    void aRuleOnATriggerThatIsNotAboutAnItemCannotNameAStack() throws IOException {
        var broken = text("""
                welcome:
                  behavior:
                    - id: reads_item
                      on: player.join
                      do:
                        - run_command: "say ${vars.item.itemvars.level}"
                    - id: writes_item
                      on: player.join
                      do:
                        - set_var: {var: item.level, op: add, value: 1}
                        - set_var: {var: "itemvars:title", value: "x"}
                """, Path.of("rules/welcome.yml"), "rule", "welcome");
        var resources = new java.util.ArrayList<>(fixturePack());
        resources.add(broken);

        RuntimeRegistries shadow = shadow(resources);

        var problems = diagnostics.recent(64);
        assertThat(problems).extracting(problem -> problem.code().code()).containsOnly("MTR-0060");
        assertThat(problems).hasSize(3); // the template read, and each of the two writes
        assertThat(problems).allSatisfy(problem -> assertThat(problem.message()).contains("'item'"));
        assertThat(problems).extracting(Diagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("set_var writes the item variable itemvars:level"));
        // MTR-0060 is a warning: the rules still load and read the default at run time
        assertThat(shadow.view(WorldRules.KEY).forTrigger(EventTypes.PLAYER_JOIN)).hasSize(2);
    }
}
