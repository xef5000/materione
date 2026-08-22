package dev.materione.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import org.junit.jupiter.api.Test;

/** Shadow-swap semantics (plugin.md §4.5): diff, atomic apply, rollback. */
class ContentRuntimeTest {

    private RuntimeItem item(String value, String material) {
        return new RuntimeItem(
                NamespacedId.parse("sample:" + value), material, null, null, java.util.List.of(),
                RuntimeItem.hashOf(NamespacedId.parse("sample:" + value), material, null, null, java.util.List.of()));
    }

    @Test
    void applyReportsAddRemoveChange() {
        var runtime = new ContentRuntime();
        var v1 = RuntimeRegistries.builder()
                .item(item("a", "PAPER"))
                .item(item("b", "STONE"))
                .build();
        var first = runtime.apply(v1, "sha-1");
        assertThat(first.added()).isEqualTo(2);
        assertThat(first.removed()).isZero();
        assertThat(first.changed()).isZero();

        var v2 = RuntimeRegistries.builder()
                .item(item("a", "PAPER"))       // unchanged
                .item(item("b", "DIAMOND"))     // changed
                .item(item("c", "DIRT"))        // added
                .build();
        var second = runtime.apply(v2, "sha-2");
        assertThat(second.added()).isEqualTo(1);
        assertThat(second.removed()).isZero();
        assertThat(second.changed()).isEqualTo(1);

        var v3 = RuntimeRegistries.builder().item(item("c", "DIRT")).build();
        var third = runtime.apply(v3, "sha-3");
        assertThat(third.added()).isZero();
        assertThat(third.removed()).isEqualTo(2);
    }

    @Test
    void swapIsObservableImmediatelyAndRollbackRestores() {
        var runtime = new ContentRuntime();
        var first = RuntimeRegistries.builder().item(item("a", "PAPER")).build();
        runtime.apply(first, "sha-1");
        var second = RuntimeRegistries.builder().item(item("b", "STONE")).build();
        runtime.apply(second, "sha-2");
        assertThat(runtime.registries().item(NamespacedId.parse("sample:b"))).isPresent();
        assertThat(runtime.livePackSha256()).isEqualTo("sha-2");

        // apply failed after the swap: rollback restores the previous shadow
        runtime.rollback(first, "sha-1");
        assertThat(runtime.registries().item(NamespacedId.parse("sample:a"))).isPresent();
        assertThat(runtime.registries().item(NamespacedId.parse("sample:b"))).isEmpty();
        assertThat(runtime.livePackSha256()).isEqualTo("sha-1");
    }

    @Test
    void definitionHashTracksAppearanceOnly() {
        var id = NamespacedId.parse("sample:a");
        String hash = RuntimeItem.hashOf(id, "PAPER", null, "Name", java.util.List.of("L1"));
        String same = RuntimeItem.hashOf(id, "PAPER", null, "Name", java.util.List.of("L1"));
        String different = RuntimeItem.hashOf(id, "PAPER", null, "Renamed", java.util.List.of("L1"));
        assertThat(hash).isEqualTo(same).isNotEqualTo(different);
    }
}
