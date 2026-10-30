package dev.materione.plugin.logic.condition;

import static dev.materione.plugin.logic.condition.ConditionFixtures.params;
import static dev.materione.plugin.logic.condition.ConditionFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.plugin.logic.action.PlayerFake;
import dev.materione.plugin.logic.support.ItemCatalog;
import org.junit.jupiter.api.Test;

/**
 * {@code has_item} (logic.md §19): parse accept and reject, and the false answers. The matching rules themselves
 * (lore, case, strict, slot inclusion) are unit-tested on the shared matcher, {@code ItemMatchTest}, since an
 * inventory of real stacks needs a server.
 */
class HasItemConditionTest {

    private final HasItemCondition condition = new HasItemCondition(ItemCatalog.none());

    @Test
    void theShorthandIsTheItemAndTheDefaultsMatchTakeItem() {
        var parsed = condition.parse(reader(params("item", "minecraft:diamond")));
        assertThat(parsed.item().source()).isEqualTo("minecraft:diamond");
        assertThat(parsed.amount().wholeValue(null).getAsInt()).isEqualTo(1);
        assertThat(parsed.nameContains()).isEmpty();
        assertThat(parsed.loreContains()).isEmpty();
        assertThat(parsed.ignoreCase()).isTrue();
        assertThat(parsed.strict()).isTrue();
        assertThat(parsed.armour()).isTrue();
        assertThat(parsed.offhand()).isTrue();
    }

    @Test
    void theMappingCarriesEveryOption() {
        var parsed = condition.parse(reader(params("item", "shop:ruby", "amount", 3, "name_contains", "gem",
                "lore_contains", "Rare", "ignore_case", false, "strict", false, "armor", false, "offhand", false)));
        assertThat(parsed.nameContains()).isPresent();
        assertThat(parsed.loreContains()).isPresent();
        assertThat(parsed.ignoreCase()).isFalse();
        assertThat(parsed.strict()).isFalse();
        assertThat(parsed.armour()).isFalse();
        assertThat(parsed.offhand()).isFalse();
    }

    @Test
    void anEmptyInventoryHoldsNoItem() {
        var fake = new PlayerFake();
        assertThat(condition.test(condition.parse(reader(params("item", "minecraft:diamond"))), fake.context()))
                .isFalse();
    }

    @Test
    void anUnresolvableMaterioneItemIsFalseNotAnError() {
        var fake = new PlayerFake();
        var parsed = condition.parse(reader(params("item", "ruby")));
        assertThat(condition.test(parsed, fake.context())).isFalse();
    }

    @Test
    void withoutAPlayerItIsFalse() {
        var parsed = condition.parse(reader(params("item", "minecraft:diamond")));
        assertThat(condition.test(parsed, PlayerFake.noPlayer())).isFalse();
    }

    @Test
    void rejectsAMissingItemAMalformedVanillaNameAndAZeroAmount() {
        assertThatThrownBy(() -> condition.parse(reader(params("amount", 2))))
                .hasMessage("item is required");
        assertThatThrownBy(() -> condition.parse(reader(params("item", "minecraft:not a item!"))))
                .hasMessageContaining("is not a vanilla item id");
        assertThatThrownBy(() -> condition.parse(reader(params("item", "minecraft:diamond", "amount", 0))))
                .hasMessageContaining("amount must be a whole number");
        assertThatThrownBy(() -> condition.parse(reader(params("item", "minecraft:diamond", "strict", "yes"))))
                .hasMessage("strict must be true or false");
    }
}
