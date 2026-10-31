package dev.materione.plugin.logic.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The slot layout of a player's inventory (0..35 storage, 36..39 armour, 40 offhand) and the room arithmetic
 * behind {@code has_item} and {@code has_item_space}. Pure, over {@link ItemView}s.
 */
class ItemSlotsTest {

    private static final NamespacedId RUBY = NamespacedId.of("shop", "ruby");

    /** A 41-slot inventory with the given views at the given indexes, empty elsewhere. */
    private static List<ItemView> inventory(java.util.Map<Integer, ItemView> at) {
        var slots = new ArrayList<ItemView>(Collections.nCopies(41, (ItemView) null));
        at.forEach(slots::set);
        return slots;
    }

    private static ItemView diamonds(int amount) {
        return new ItemView(Optional.empty(), "DIAMOND", Optional.empty(), amount);
    }

    @Test
    void armourAndOffhandCanBeLeftOutWithoutMovingAnyOtherSlot() {
        var slots = inventory(java.util.Map.of(0, diamonds(1), 36, diamonds(2), 40, diamonds(3)));

        var noArmour = ItemSlots.included(slots, false, true);
        assertThat(noArmour).hasSize(41);
        assertThat(noArmour.get(0)).isNotNull();
        assertThat(noArmour.get(36)).isNull();
        assertThat(noArmour.get(40)).isNotNull();

        var noOffhand = ItemSlots.included(slots, true, false);
        assertThat(noOffhand.get(36)).isNotNull();
        assertThat(noOffhand.get(40)).isNull();

        assertThat(ItemSlots.included(slots, true, true)).isEqualTo(slots);
    }

    @Test
    void theStorageIsTheMainSlotsOnly() {
        var slots = inventory(java.util.Map.of(0, diamonds(1), 35, diamonds(1), 36, diamonds(1)));
        var storage = ItemSlots.storage(slots);
        assertThat(storage.get(0)).isNotNull();
        assertThat(storage.get(35)).isNotNull();
        assertThat(storage.get(36)).isNull();
        assertThat(ItemSlots.freeSlots(storage)).isEqualTo(34);
    }

    @Test
    void anEmptyStorageHasAllSlotsFreeAndRoomForAFullStackEach() {
        var storage = ItemSlots.storage(inventory(java.util.Map.of()));
        assertThat(ItemSlots.freeSlots(storage)).isEqualTo(36);
        assertThat(ItemSlots.room(storage, view -> true, 64)).isEqualTo(36L * 64);
        assertThat(ItemSlots.room(storage, view -> true, 16)).isEqualTo(36L * 16);
    }

    @Test
    void aPartialMatchingStackHoldsOnlyTheRestOfItsOwnStack() {
        var rubies = new ItemView(Optional.of(RUBY), "PAPER", Optional.empty(), 60);
        var slots = inventory(java.util.Map.of(0, rubies, 1, diamonds(64)));
        var storage = ItemSlots.storage(slots);
        var wanted = ItemMatch.of(new ItemRef.Materione(RUBY), Optional.empty()).predicate();

        // slot 0 holds 4 more rubies, slot 1 holds diamonds (no room for rubies), slots 2..35 are empty
        assertThat(ItemSlots.room(storage, wanted, 64)).isEqualTo(4L + 34L * 64);
    }

    @Test
    void aFullStackOfTheSameItemHasNoRoom() {
        var slots = inventory(java.util.Map.of(0, new ItemView(Optional.empty(), "DIAMOND", Optional.empty(), 64)));
        var storage = ItemSlots.storage(slots);
        var wanted = ItemMatch.of(new ItemRef.Vanilla("DIAMOND"), Optional.empty()).predicate();
        assertThat(ItemSlots.room(storage, wanted, 64)).isEqualTo(35L * 64);
    }
}
