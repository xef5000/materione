package dev.materione.plugin.items;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Operator-typed item references → live ids: exact, bare, suffix, ambiguity. */
class ItemIdResolverTest {

    private static final List<RuntimeItem> ITEMS = List.of(
            item("sample", "ruby_sword"),
            item("world", "ration"),
            item("corpus", "gem_red"),
            item("other", "gem_red"));

    private static RuntimeItem item(String namespace, String value) {
        return new RuntimeItem(NamespacedId.of(namespace, value), "STONE", null, null,
                List.of(), "hash");
    }

    @Test
    void namespacedReferenceMustBeLive() {
        assertThat(ItemIdResolver.resolve("sample:ruby_sword", ITEMS).status())
                .isEqualTo(ItemIdResolver.Status.RESOLVED);
        assertThat(ItemIdResolver.resolve("sample:nope", ITEMS).status())
                .isEqualTo(ItemIdResolver.Status.UNKNOWN);
        assertThat(ItemIdResolver.resolve("not an id at all!", ITEMS).status())
                .isEqualTo(ItemIdResolver.Status.UNKNOWN);
    }

    @Test
    void bareValueMatchesAcrossPacks() {
        var result = ItemIdResolver.resolve("RATION", ITEMS);
        assertThat(result.status()).isEqualTo(ItemIdResolver.Status.RESOLVED);
        assertThat(result.id()).isEqualTo(NamespacedId.of("world", "ration"));
    }

    @Test
    void ambiguousBareValueListsCandidatesInsteadOfGuessing() {
        var result = ItemIdResolver.resolve("gem_red", ITEMS);
        assertThat(result.status()).isEqualTo(ItemIdResolver.Status.AMBIGUOUS);
        assertThat(result.candidates()).containsExactlyInAnyOrder(
                NamespacedId.of("corpus", "gem_red"), NamespacedId.of("other", "gem_red"));
    }

    @Test
    void uniqueSuffixResolves() {
        var result = ItemIdResolver.resolve("sword", ITEMS);
        assertThat(result.status()).isEqualTo(ItemIdResolver.Status.RESOLVED);
        assertThat(result.id()).isEqualTo(NamespacedId.of("sample", "ruby_sword"));
    }

    @Test
    void ambiguousSuffixAlsoReports() {
        var result = ItemIdResolver.resolve("_red", ITEMS);
        assertThat(result.status()).isEqualTo(ItemIdResolver.Status.AMBIGUOUS);
    }

    @Test
    void missesAndBlanksStayUnknown() {
        assertThat(ItemIdResolver.resolve("diamond", ITEMS).status())
                .isEqualTo(ItemIdResolver.Status.UNKNOWN);
        assertThat(ItemIdResolver.resolve("", ITEMS).status())
                .isEqualTo(ItemIdResolver.Status.UNKNOWN);
        assertThat(ItemIdResolver.resolve("   ", ITEMS).status())
                .isEqualTo(ItemIdResolver.Status.UNKNOWN);
    }
}
