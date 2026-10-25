package dev.materione.plugin.runtime.mechanics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.registry.RegistryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MechanicsResolverTest {
    private static final NamespacedId ITEM = NamespacedId.parse("sample:sword");
    private final RegistryService registries = new RegistryService();
    private final Registry<Mechanic> registry = registries.registry(MechanicsResolver.REGISTRY_KEY, Mechanic.class);
    private final MechanicsResolver resolver = new MechanicsResolver(Optional.of(registry));
    private final List<String> rejects = new ArrayList<>();

    @Test
    void resolvesEveryBuiltinThroughItsOwnParser() {
        NativeMechanics.all().forEach(mechanic -> register("materione:" + mechanic.name(), mechanic));
        var active = resolve(List.of(
                Map.of("soulbound", Map.of()),
                Map.of("durability", Map.of("max", 500)),
                Map.of("food", Map.of("nutrition", 6, "saturation", 8.0)),
                Map.of("backpack", Map.of("rows", 3)),
                Map.of("commands", Map.of("on_right_click", List.of("spawn")))));

        assertThat(rejects).isEmpty();
        assertThat(active).hasSize(5);
        assertThat(active.get(1).params()).containsEntry("max", 500);
        assertThat(active.get(4).behaviorRules()).hasSize(1);
        assertThat(active.get(2).itemModifiers()).hasSize(1);
    }

    @Test
    void namespacedAddonCompilesToTypedInstanceExactlyOnce() {
        var parses = new AtomicInteger();
        register("addon:charge", new Charge(parses));
        var active = resolve(List.of(Map.of("addon:charge", Map.of("power", 7))));
        var snapshot = new ItemMechanics(Map.of(ITEM, active));

        assertThat(snapshot.onItem(ITEM, NamespacedId.parse("addon:charge"), Power.class))
                .contains(new Power(7, "ready"));
        assertThat(snapshot.onItem(ITEM, NamespacedId.parse("other:charge"), Power.class)).isEmpty();
        assertThat(snapshot.onItem(ITEM, NamespacedId.parse("addon:charge"), Other.class)).isEmpty();
        assertThat(parses).hasValue(1);
        assertThat(rejects).isEmpty();
    }

    @Test
    void shortNamesOnlyAliasTheMaterioneNamespace() {
        register("addon:soulbound", new Charge(new AtomicInteger()));
        var active = resolve(List.of(Map.of("soulbound", Map.of("power", 2)),
                Map.of("addon:soulbound", Map.of("power", 3))));

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().id()).isEqualTo(NamespacedId.parse("addon:soulbound"));
        assertThat(rejects).singleElement().asString().contains("materione:soulbound");
    }

    @Test
    void duplicateFullIdentityIsRejectedIncludingItsShortAlias() {
        register("materione:charge", new Charge(new AtomicInteger()));
        var active = resolve(List.of(Map.of("charge", Map.of("power", 1)),
                Map.of("materione:charge", Map.of("power", 2))));

        assertThat(active).hasSize(1);
        assertThat(rejects).singleElement().asString().contains("duplicate");
    }

    @Test
    void schemaEnforcesRequiredTypesAndSuppliesDefaultsBeforeAddonParsing() {
        register("addon:charge", new Charge(new AtomicInteger()));
        var active = resolve(List.of(
                Map.of("addon:charge", Map.of()),
                Map.of("addon:charge", Map.of("power", 2.5)),
                Map.of("addon:charge", Map.of("power", 4, "label", false)),
                Map.of("addon:charge", Map.of("power", 8))));

        assertThat(rejects).hasSize(3);
        assertThat(active).singleElement().extracting(ActiveMechanic::instance)
                .isEqualTo(new Power(8, "ready"));
    }

    @Test
    void malformedContentIsReportedWithoutLosingValidSiblings() {
        register("materione:charge", new Charge(new AtomicInteger()));
        var active = resolve(List.of("not-a-map", Map.of(1, Map.of()),
                Map.of("BAD:name", Map.of()), Map.of("charge", false),
                Map.of("charge", Map.of("power", 3))));

        assertThat(active).hasSize(1);
        assertThat(rejects).hasSize(4);
        resolve(Map.of("charge", Map.of("power", 3)));
        assertThat(rejects).last().asString().contains("list");
    }

    @Test
    void missingRegistryReportsEveryDeclaration() {
        var empty = new MechanicsResolver(Optional.empty());
        assertThat(empty.resolve(ITEM, List.of(Map.of("soulbound", Map.of())),
                (name, reason) -> rejects.add(reason))).isEmpty();
        assertThat(rejects).singleElement().asString().contains("unknown mechanic");
    }

    @Test
    void extensionFailureAndMetadataOnlyImplementationsAreNotSuccessShaped() {
        register("addon:metadata", metadataOnly());
        register("addon:broken", new Charge(new AtomicInteger()) {
            @Override
            public MechanicInstance parse(MechanicConfiguration configuration) {
                throw new IllegalStateException("broken parser");
            }
        });
        register("addon:good", new Charge(new AtomicInteger()));
        var active = resolve(List.of(Map.of("addon:metadata", Map.of()),
                Map.of("addon:broken", Map.of("power", 1)),
                Map.of("addon:good", Map.of("power", 2))));

        assertThat(active).hasSize(1);
        assertThat(rejects).hasSize(2);
        assertThat(rejects.getFirst()).contains("does not implement parsing");
        assertThat(rejects.getLast()).contains("broken parser");
    }

    @Test
    void nestedSchemaIsValidatedAndDefaultsAreVisibleToTheParser() {
        register("addon:nested", new Charge(new AtomicInteger()) {
            @Override
            public SelfDescription describe() {
                return new SelfDescription("Nested", "", List.of(new ParameterSchema(
                        "effects", ParameterType.LIST, true, null, "", null,
                        List.of(ParameterSchema.of("type", ParameterType.STRING, true)))), "1");
            }

            @Override
            public MechanicInstance parse(MechanicConfiguration configuration) {
                return new Power(configuration.sections("effects").size(), "nested");
            }
        });
        var active = resolve(List.of(Map.of("addon:nested",
                Map.of("effects", List.of(Map.of("amplifier", 1))))));
        assertThat(active).isEmpty();
        assertThat(rejects).singleElement().asString().contains("type");
    }

    private List<ActiveMechanic> resolve(Object declarations) {
        return resolver.resolve(ITEM, declarations, (name, reason) -> rejects.add(name + ": " + reason));
    }

    private void register(String id, Mechanic mechanic) {
        registry.register(NamespacedId.parse(id), mechanic, mechanic.describe(), "test");
    }

    private static Mechanic metadataOnly() {
        return new Mechanic() {
            @Override
            public String name() {
                return "metadata";
            }

            @Override
            public SelfDescription describe() {
                return SelfDescription.of("Metadata", "");
            }
        };
    }

    private record Power(int power, String label) implements MechanicInstance {}
    private record Other() implements MechanicInstance {}

    private static class Charge implements Mechanic {
        private final AtomicInteger parses;

        Charge(AtomicInteger parses) {
            this.parses = parses;
        }

        @Override
        public String name() {
            return "charge";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Charge", "", List.of(
                    ParameterSchema.of("power", ParameterType.INTEGER, true),
                    new ParameterSchema("label", ParameterType.STRING, false, "ready", "", null, null)), "1");
        }

        @Override
        public MechanicInstance parse(MechanicConfiguration configuration) {
            parses.incrementAndGet();
            return new Power(configuration.integer("power", -1, 0, 100),
                    configuration.text("label", "ready"));
        }
    }
}
