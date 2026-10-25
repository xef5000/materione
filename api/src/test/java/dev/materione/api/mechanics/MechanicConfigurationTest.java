package dev.materione.api.mechanics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MechanicConfigurationTest {
    private static final NamespacedId ITEM = NamespacedId.parse("example:item");

    @Test
    void snapshotsNestedParametersWithoutMutatingTheCaller() {
        var child = new LinkedHashMap<String, Object>(Map.of("type", "speed"));
        var effects = new ArrayList<>(List.of(child));
        var raw = new LinkedHashMap<String, Object>(Map.of("effects", effects));
        var configuration = new MechanicConfiguration(ITEM, raw);
        child.put("type", "poison");
        effects.clear();
        raw.clear();

        assertThat(configuration.sections("effects").getFirst().text("type", ""))
                .isEqualTo("speed");
        assertThatThrownBy(() -> configuration.parameters().put("bad", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> configuration.sections("effects").getFirst().parameters().put("bad", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void typedReadsAreStrictAndRejectNonFiniteOrFractionalValues() {
        for (Object value : List.of(1.5, Double.NaN, Double.POSITIVE_INFINITY, "3", true, Long.MAX_VALUE)) {
            var configuration = new MechanicConfiguration(ITEM, Map.of("value", value));
            assertThatThrownBy(() -> configuration.integer("value", 0, 0, 20))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("value");
        }
        var configuration = new MechanicConfiguration(ITEM, Map.of("value", Double.NaN));
        assertThatThrownBy(() -> configuration.decimal("value", 0, 0, 40))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MechanicConfiguration(ITEM, Map.of("flag", "false")).bool("flag", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void suppliesExplicitDefaultsAndValidatesListChildren() {
        var configuration = new MechanicConfiguration(ITEM, Map.of());
        assertThat(configuration.integer("rows", 3, 1, 6)).isEqualTo(3);
        assertThat(configuration.bool("enabled", false)).isFalse();
        assertThat(configuration.strings("commands")).isEmpty();
        assertThatThrownBy(() -> new MechanicConfiguration(ITEM,
                Map.of("commands", List.of("valid", 3))).strings("commands"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MechanicConfiguration(ITEM,
                Map.of("effects", List.of("not-a-map"))).sections("effects"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
