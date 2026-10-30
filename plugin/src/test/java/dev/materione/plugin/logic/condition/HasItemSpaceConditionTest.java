package dev.materione.plugin.logic.condition;

import static dev.materione.plugin.logic.condition.ConditionFixtures.params;
import static dev.materione.plugin.logic.condition.ConditionFixtures.reader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.plugin.logic.action.PlayerFake;
import dev.materione.plugin.logic.support.ItemCatalog;
import org.junit.jupiter.api.Test;

/**
 * {@code has_item_space} (logic.md §19). The empty-inventory answers use the fake player (36 free storage
 * slots); the room arithmetic is unit-tested on {@code ItemSlots}, since a partially full inventory needs real
 * stacks.
 */
class HasItemSpaceConditionTest {

    private final HasItemSpaceCondition condition = new HasItemSpaceCondition(ItemCatalog.none());

    @Test
    void theShorthandIsAFreeSlotCount() {
        var parsed = condition.parse(reader(params("slots", 2)));
        assertThat(parsed.slots()).isPresent();
        assertThat(parsed.item()).isEmpty();
    }

    @Test
    void anEmptyInventoryHasEveryStorageSlotFree() {
        var fake = new PlayerFake();
        assertThat(condition.test(condition.parse(reader(params("slots", 36))), fake.context())).isTrue();
        assertThat(condition.test(condition.parse(reader(params("slots", 0))), fake.context())).isTrue();
    }

    @Test
    void anUnresolvableItemIsFalseNotAnError() {
        var fake = new PlayerFake();
        var parsed = condition.parse(reader(params("item", "ruby", "amount", 5)));
        assertThat(condition.test(parsed, fake.context())).isFalse();
    }

    @Test
    void withoutAPlayerItIsFalse() {
        assertThat(condition.test(condition.parse(reader(params("slots", 1))), PlayerFake.noPlayer())).isFalse();
    }

    @Test
    void rejectsBothOrNeitherOfSlotsAndItem() {
        assertThatThrownBy(() -> condition.parse(reader(params("slots", 1, "item", "minecraft:diamond"))))
                .hasMessage("give slots or item, not both");
        assertThatThrownBy(() -> condition.parse(reader(params())))
                .hasMessage("give slots (free slots) or item (room for an item)");
        assertThatThrownBy(() -> condition.parse(reader(params("amount", 3))))
                .hasMessage("give slots (free slots) or item (room for an item)");
    }

    @Test
    void rejectsAmountWithSlotsAndSlotsOutsideTheStorage() {
        assertThatThrownBy(() -> condition.parse(reader(params("slots", 1, "amount", 3))))
                .hasMessage("amount goes with item, not with slots");
        assertThatThrownBy(() -> condition.parse(reader(params("slots", 37))))
                .hasMessageContaining("slots must be a whole number in 0..36");
        assertThatThrownBy(() -> condition.parse(reader(params("item", "minecraft:paper", "amount", 0))))
                .hasMessageContaining("amount must be a whole number");
    }
}
