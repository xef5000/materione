package dev.materione.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NamespacedIdTest {

    @Test
    void parsesValidIds() {
        var id = NamespacedId.parse("mystuff:ruby_sword");
        assertThat(id.namespace()).isEqualTo("mystuff");
        assertThat(id.value()).isEqualTo("ruby_sword");
        assertThat(id.toString()).isEqualTo("mystuff:ruby_sword");
    }

    @Test
    void roundTripsThroughToString() {
        var id = NamespacedId.parse("ns:a.b-c_d");
        assertThat(NamespacedId.parse(id.toString())).isEqualTo(id);
    }

    @Test
    void valueSemantics() {
        assertThat(NamespacedId.parse("ns:x")).isEqualTo(NamespacedId.of("ns", "x"));
        assertThat(NamespacedId.parse("ns:x")).isNotEqualTo(NamespacedId.of("ns", "y"));
    }

    @Test
    void rejectsMalformed() {
        assertThatThrownBy(() -> NamespacedId.parse("nocolon"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NamespacedId.parse("a:b:c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NamespacedId.parse("Upper:x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NamespacedId.parse("ns:UPPER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NamespacedId.parse("ns:with space"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NamespacedId("", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservedNamespaceIsDetectableButNotRejectedByTheType() {
        // The type stays expressive; registries do the rejecting.
        var id = NamespacedId.parse("minecraft:stone");
        assertThat(id.isReservedNamespace()).isTrue();
        assertThat(NamespacedId.of("materione", "backpack").isReservedNamespace()).isFalse();
    }
}
