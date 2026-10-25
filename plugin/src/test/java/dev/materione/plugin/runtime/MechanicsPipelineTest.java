package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.mechanics.ItemModifier;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PackManifest;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.MaterioneStacks;
import dev.materione.plugin.runtime.behavior.ItemBehaviors;
import dev.materione.plugin.runtime.mechanics.ItemMechanics;
import dev.materione.plugin.runtime.mechanics.builtin.CommandsMechanic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MechanicsPipelineTest {
    private static final NamespacedId ITEM = NamespacedId.parse("example:wand");
    private static final NamespacedId MECHANIC = NamespacedId.parse("example:charge");
    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final PipelineService pipeline = new PipelineService(null, diagnostics,
            new QuarantineTracker(), registries, null,
            new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    @Test
    void addonContributesTypedBindingsSharedRulesAndEveryFactoryHook() throws IOException {
        pipeline.registerBuiltins();
        var mechanic = new Charge();
        registries.registry(RegistryKeys.MECHANICS, Mechanic.class)
                .register(MECHANIC, mechanic, mechanic.describe(), "example");
        RuntimeRegistries shadow = shadow(fixture());
        var item = shadow.item(ITEM).orElseThrow();

        assertThat(shadow.view(ItemMechanics.KEY).onItem(ITEM, MECHANIC, Binding.class))
                .contains(new Binding(4));
        assertThat(shadow.view(ItemBehaviors.KEY).forItem(ITEM).orElseThrow()).hasSize(2);
        assertThat(item.itemModifiers()).hasSize(1);
        assertThat(MaterioneStacks.specOf(item).itemModifiers()).containsExactlyElementsOf(item.itemModifiers());
        assertThat(diagnostics.recent(64)).isEmpty();
    }

    @Test
    void parameterOnlyChangeCreatesANewRuntimeDiffAndKeepsOldSnapshotImmutable() throws IOException {
        pipeline.registerBuiltins();
        var mechanic = new Charge();
        registries.registry(RegistryKeys.MECHANICS, Mechanic.class)
                .register(MECHANIC, mechanic, mechanic.describe(), "example");
        var original = shadow(fixture());
        var changed = shadow(fixture().replace("power: 4", "power: 9"));
        var runtime = new ContentRuntime();
        runtime.apply(original, "identical-pack-bytes");

        assertThat(runtime.diffAgainst(changed, "identical-pack-bytes").changed()).isEqualTo(1);
        runtime.apply(changed, "identical-pack-bytes");
        assertThat(original.view(ItemMechanics.KEY).onItem(ITEM, MECHANIC, Binding.class))
                .contains(new Binding(4));
        assertThat(runtime.registries().view(ItemMechanics.KEY).onItem(ITEM, MECHANIC, Binding.class))
                .contains(new Binding(9));
        assertThat(shadow(fixture()).item(ITEM).orElseThrow().definitionHash())
                .isEqualTo(original.item(ITEM).orElseThrow().definitionHash());
    }

    @Test
    void malformedMechanicsAreDiagnosedRatherThanLostByTheItemParser() {
        pipeline.registerBuiltins();
        var shadow = shadow("wand:\n  material: STICK\n  mechanics: [not-a-map]\n");
        assertThat(shadow.item(ITEM)).isPresent();
        assertThat(diagnostics.recent(64)).singleElement()
                .extracting(diagnostic -> diagnostic.code().toString()).isEqualTo("MTR-0027");
    }

    @Test
    void repeatedCommandsPreserveBothExecutionModesAndHaveDistinctRuleIdentities() {
        pipeline.registerBuiltins();
        var commands = new CommandsMechanic();
        registries.registry(RegistryKeys.MECHANICS, Mechanic.class)
                .register(NamespacedId.parse("materione:commands"), commands, commands.describe(), "materione");
        var shadow = shadow("""
                wand:
                  material: BLAZE_ROD
                  mechanics:
                    - commands:
                        on_left_click: ["say console"]
                    - materione:commands:
                        as_player: true
                        on_left_click: ["materione version"]
                """);
        var rules = shadow.view(ItemBehaviors.KEY).forItem(ITEM).orElseThrow();

        assertThat(rules).hasSize(2);
        assertThat(rules).extracting(rule -> rule.id()).doesNotHaveDuplicates();
        assertThat(rules).extracting(rule -> rule.actions().getFirst().param("as"))
                .containsExactly("console", "player");
        assertThat(rules).extracting(rule -> rule.actions().getFirst().param("command"))
                .containsExactly("say console", "materione version");
        assertThat(shadow.view(ItemMechanics.KEY).forItem(ITEM)).hasSize(2);
        assertThat(diagnostics.recent(64)).isEmpty();
    }

    @Test
    void malformedAddonRuleDoesNotAbortItsSiblingsOrAuthoredBehavior() throws IOException {
        pipeline.registerBuiltins();
        var mechanic = new Charge() {
            @Override
            public MechanicInstance parse(MechanicConfiguration configuration) {
                return new MechanicInstance() {
                    @Override
                    public List<Map<String, Object>> behaviorRules() {
                        return List.of(
                                Map.of("on", "click:right", "do", java.util.Collections.singletonList(null)),
                                Map.of("on", "click:right", "do", List.of(Map.of("message", "valid-sibling"))));
                    }
                };
            }
        };
        registries.registry(RegistryKeys.MECHANICS, Mechanic.class)
                .register(MECHANIC, mechanic, mechanic.describe(), "example");
        var shadow = shadow(fixture());

        assertThat(shadow.item(ITEM)).isPresent();
        assertThat(shadow.view(ItemBehaviors.KEY).forItem(ITEM).orElseThrow()).hasSize(2);
        assertThat(diagnostics.recent(64)).singleElement()
                .extracting(diagnostic -> diagnostic.code().toString()).isEqualTo("MTR-0021");
    }

    @Test
    void replacementRegistrationTriggersReconciliationEvenWithIdenticalParameters() throws IOException {
        pipeline.registerBuiltins();
        var registry = registries.registry(RegistryKeys.MECHANICS, Mechanic.class);
        var first = new Charge();
        registry.register(MECHANIC, first, first.describe(), "example");
        var original = shadow(fixture());
        assertThat(registries.unregisterBounded(RegistryKeys.MECHANICS, Mechanic.class, MECHANIC, first)).isTrue();
        var replacement = new Charge();
        registry.register(MECHANIC, replacement, replacement.describe(), "example");
        var changed = shadow(fixture());
        var runtime = new ContentRuntime();
        runtime.apply(original, "identical-pack-bytes");

        assertThat(runtime.diffAgainst(changed, "identical-pack-bytes").changed()).isEqualTo(1);
        assertThat(original.view(ItemMechanics.KEY).onItem(ITEM, MECHANIC, Binding.class)).isEmpty();
        assertThat(original.view(ItemBehaviors.KEY).forItem(ITEM).orElseThrow()).hasSize(1);
        assertThat(changed.view(ItemBehaviors.KEY).forItem(ITEM).orElseThrow()).hasSize(2);
    }

    private RuntimeRegistries shadow(String yaml) {
        var document = LosslessYaml.parse(yaml);
        var resource = new ContentResource(ITEM, "item", Path.of("items", "wand.yml"), document);
        var manifest = new PackManifest("example", "1", 1, List.of(), LosslessYaml.parse("id: example\n"));
        return pipeline.buildShadow(List.of(new ContentPack("example", Path.of("example"), manifest,
                List.of(resource))));
    }

    private static String fixture() throws IOException {
        return Files.readString(Path.of(System.getProperty("materione.fixtures.root"))
                .resolve("mechanics").resolve("addon-item.yml"));
    }

    private record Binding(int power) implements MechanicInstance {
        @Override
        public List<Map<String, Object>> behaviorRules() {
            return List.of(Map.of("on", "click:right", "do", List.of(Map.of("message", "power=" + power))));
        }

        @Override
        public List<ItemModifier> itemModifiers() {
            return List.of(stack -> {});
        }
    }

    private static class Charge implements Mechanic {
        @Override
        public String name() {
            return "charge";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Charge", "", List.of(
                    ParameterSchema.of("power", ParameterType.INTEGER, true)), "1");
        }

        @Override
        public MechanicInstance parse(MechanicConfiguration configuration) {
            return new Binding(configuration.integer("power", -1, 0, 100));
        }
    }
}
