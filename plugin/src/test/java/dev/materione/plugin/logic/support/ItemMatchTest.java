package dev.materione.plugin.logic.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shared item matcher (logic.md §17 decision 2, §19): the take defaults are unchanged, and the options {@code
 * has_item} adds mean what they say. Pure, so no server is needed.
 */
class ItemMatchTest {

    private static final NamespacedId RUBY = NamespacedId.of("shop", "ruby");

    private static ItemView plainPaper(int amount) {
        return new ItemView(Optional.empty(), "PAPER", Optional.empty(), amount);
    }

    private static ItemView ruby(String name, List<String> lore) {
        return new ItemView(Optional.of(RUBY), "PAPER", Optional.ofNullable(name), 1, lore);
    }

    @Test
    void theTakeDefaultsMatchVanillaStacksOnlyAndIgnoreCaseForTheName() {
        var paper = new ItemRef.Vanilla("PAPER");
        var match = ItemMatch.of(paper, Optional.of("GEM"));

        // a custom item shaped like paper is not paper for a take (logic.md §17 decision 2)
        assertThat(match.predicate().test(ruby("A Gem", List.of()))).isFalse();
        assertThat(match.predicate().test(new ItemView(Optional.empty(), "PAPER", Optional.of("a gem"), 1)))
                .isTrue();
        assertThat(ItemMatch.of(paper, Optional.empty()).predicate().test(plainPaper(1))).isTrue();
    }

    @Test
    void theInventoryPlanWantedIsTheSameMatcherAsTheTakeDefaults() {
        var ruby = new ItemRef.Materione(RUBY);
        var viaPlan = InventoryPlan.wanted(ruby, Optional.of("gem"));
        var viaMatch = ItemMatch.of(ruby, Optional.of("gem")).predicate();
        for (ItemView view : List.of(ruby("Red Gem", List.of()), ruby("Stone", List.of()), plainPaper(2))) {
            assertThat(viaPlan.test(view)).isEqualTo(viaMatch.test(view));
        }
    }

    @Test
    void loreContainsMatchesAnyLineAndFollowsTheCaseSetting() {
        var ruby = new ItemRef.Materione(RUBY);
        var withLore = ruby("Gem", List.of("A line", "Rare find"));
        var loreOnly = new ItemMatch(ruby, Optional.empty(), Optional.of("rare"), true, true);
        var exactCase = new ItemMatch(ruby, Optional.empty(), Optional.of("rare"), false, true);

        assertThat(loreOnly.predicate().test(withLore)).isTrue();
        assertThat(exactCase.predicate().test(withLore)).isFalse();
        assertThat(loreOnly.predicate().test(ruby("Gem", List.of()))).isFalse();
    }

    @Test
    void nameAndLoreMustBothHoldWhenBothAreGiven() {
        var ruby = new ItemRef.Materione(RUBY);
        var both = new ItemMatch(ruby, Optional.of("gem"), Optional.of("rare"), true, true);
        assertThat(both.predicate().test(ruby("Red Gem", List.of("Rare")))).isTrue();
        assertThat(both.predicate().test(ruby("Red Gem", List.of("Common")))).isFalse();
        assertThat(both.predicate().test(ruby("Stone", List.of("Rare")))).isFalse();
    }

    @Test
    void strictFalseLetsAVanillaReferenceCountCustomStacksOfThatMaterial() {
        var paper = new ItemRef.Vanilla("PAPER");
        var custom = ruby("Gem", List.of());
        assertThat(new ItemMatch(paper, Optional.empty(), Optional.empty(), true, true).predicate().test(custom))
                .isFalse();
        assertThat(new ItemMatch(paper, Optional.empty(), Optional.empty(), true, false).predicate().test(custom))
                .isTrue();
    }

    @Test
    void strictHasNoEffectOnAMaterioneReference() {
        var ruby = new ItemRef.Materione(RUBY);
        var strict = new ItemMatch(ruby, Optional.empty(), Optional.empty(), true, true);
        var loose = new ItemMatch(ruby, Optional.empty(), Optional.empty(), true, false);
        assertThat(strict.predicate().test(ruby("Gem", List.of()))).isTrue();
        assertThat(loose.predicate().test(ruby("Gem", List.of()))).isTrue();
        assertThat(loose.predicate().test(plainPaper(1))).isFalse();
    }

    @Test
    void aVanillaStackWithAnotherMaterialNeverMatches() {
        var stone = new ItemMatch(new ItemRef.Vanilla("STONE"), Optional.empty(), Optional.empty(), true, false);
        assertThat(stone.predicate().test(plainPaper(1))).isFalse();
    }
}
