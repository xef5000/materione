package dev.materione.plugin.runtime.mechanics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Mechanic resolution: known names resolve with parameters, unknown names
 * and parameter violations are rejected through the sink (MTR-0027) and
 * never throw — fail-soft per definition (D6).
 */
class MechanicsResolverTest {

    private final MechanicsResolver resolver =
            new MechanicsResolver(java.util.Optional.of(new FakeRegistry()));

    @Test
    void resolvesKnownMechanicsWithParams() {
        var declarations = List.of(
                map("soulbound", Map.of()),
                map("durability", map("max", 500)),
                map("food", map("nutrition", 6, "saturation", 8.0)),
                map("backpack", map("rows", 3)),
                map("commands", map("on_right_click", List.of("spawn"))));
        var rejects = new ArrayList<String>();

        List<ActiveMechanic> active = resolver.resolve(
                NamespacedId.parse("sample:sword"), declarations, (name, reason) -> rejects.add(name));

        assertThat(rejects).isEmpty();
        assertThat(active).hasSize(5);
        assertThat(active.get(1).params()).containsEntry("max", 500);
        assertThat(active.get(4).params()).containsEntry("on_right_click", List.of("spawn"));
    }

    @Test
    void unknownMechanicIsRejectedNotFatal() {
        var declarations = List.of(
                map("soulbound", Map.of()),
                map("thor_glove", Map.of()));
        var rejects = new ArrayList<String>();

        List<ActiveMechanic> active = resolver.resolve(
                NamespacedId.parse("sample:glove"), declarations, (name, reason) -> rejects.add(name));

        assertThat(rejects).containsExactly("thor_glove");
        assertThat(active).hasSize(1);
    }

    @Test
    void parameterViolationsAreRejectedPerMechanic() {
        var rejects = new ArrayList<String>();
        var bad = new java.util.LinkedHashMap<String, Map<String, Object>>();
        bad.put("durability", map("max", -3));
        bad.put("backpack", map("rows", 9));
        bad.put("food", map("nutrition", 99, "saturation", 1));
        bad.put("commands", map("on_right_click", "not-a-list"));

        for (Map.Entry<String, Map<String, Object>> entry : bad.entrySet()) {
            resolver.resolve(
                    NamespacedId.parse("sample:x"),
                    List.of(map(entry.getKey(), entry.getValue())),
                    (name, reason) -> rejects.add(name + ": " + reason));
        }
        assertThat(rejects).hasSize(4);
        assertThat(rejects.get(0)).startsWith("durability: ");
        assertThat(rejects.get(1)).startsWith("backpack: ");
        assertThat(rejects.get(2)).startsWith("food: ");
        assertThat(rejects.get(3)).startsWith("commands: ");
    }

    @Test
    void missingRegistryResolvesToNothing() {
        var empty = new MechanicsResolver(java.util.Optional.empty());
        var rejects = new ArrayList<String>();
        List<ActiveMechanic> active = empty.resolve(
                NamespacedId.parse("sample:x"),
                List.of(map("soulbound", Map.of())),
                (name, reason) -> rejects.add(name));
        assertThat(active).isEmpty();
        assertThat(rejects).isEmpty(); // feature inactive, not an authoring error
    }

    @Test
    void validateCoversEveryNative() {
        assertThat(MechanicsResolver.validate("soulbound", Map.of())).isNull();
        assertThat(MechanicsResolver.validate(
                "food", map("nutrition", 5, "saturation", 2.5))).isNull();
        assertThat(MechanicsResolver.validate(
                "food", map("nutrition", 5, "saturation", 2.5,
                        "effects", List.of(map("type", "SPEED"))))).isNull();
        assertThat(MechanicsResolver.validate(
                "food", map("nutrition", 5, "saturation", 2.5,
                        "effects", List.of(map("amplifier", 1))))).isNotNull();
        assertThat(MechanicsResolver.validate("unknown_addon", Map.of())).isNull();
    }

    // --- helpers -------------------------------------------------------------

    private static Map<String, Object> map(Object... pairs) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /** Minimal registry stand-in exposing the five built-in mechanic names. */
    private static final class FakeRegistry
            implements dev.materione.api.registry.Registry<dev.materione.api.mechanics.Mechanic> {
        @Override
        public dev.materione.api.registry.RegistryKey key() {
            return MechanicsResolver.REGISTRY_KEY;
        }

        @Override
        public Class<dev.materione.api.mechanics.Mechanic> entryType() {
            return dev.materione.api.mechanics.Mechanic.class;
        }

        @Override
        public void register(
                NamespacedId id, dev.materione.api.mechanics.Mechanic entry,
                dev.materione.api.registry.SelfDescription description, String registeredBy) {}

        @Override
        public java.util.Optional<dev.materione.api.registry.RegistryEntry<dev.materione.api.mechanics.Mechanic>> get(
                NamespacedId id) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Collection<dev.materione.api.registry.RegistryEntry<dev.materione.api.mechanics.Mechanic>>
                entries() {
            return NativeMechanics.all().stream()
                    .map(mechanic -> new dev.materione.api.registry.RegistryEntry<>(
                            NamespacedId.of("materione", mechanic.name()), mechanic,
                            mechanic.describe(), "materione"))
                    .toList();
        }

        @Override
        public boolean isFrozen() {
            return true;
        }

        @Override
        public int size() {
            return NativeMechanics.all().size();
        }
    }
}
