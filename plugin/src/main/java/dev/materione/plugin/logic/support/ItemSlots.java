package dev.materione.plugin.logic.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Slot arithmetic over a player's inventory as {@link Stacks#contents} lays it out (logic.md §19):
 * indexes {@code 0..35} are the main storage, {@code 36..39} the armour and {@code 40} the offhand. Pure,
 * so the inclusion rules of {@code has_item} and the room rules of {@code has_item_space} are unit-tested.
 */
public final class ItemSlots {

    /** The first armour slot; the main storage is below it. */
    public static final int ARMOR_FROM = 36;
    /** The offhand slot. */
    public static final int OFFHAND = 40;
    /** One past the last storage slot: where pickups and {@code give_item} put things. */
    public static final int STORAGE_TO = 36;

    private ItemSlots() {}

    /**
     * The contents with the excluded groups blanked (null), so the same counting code sees only what the
     * author included. Positions are kept.
     */
    public static List<ItemView> included(List<ItemView> slots, boolean armour, boolean offhand) {
        var out = new ArrayList<ItemView>(slots.size());
        for (int slot = 0; slot < slots.size(); slot++) {
            boolean keep = slot == OFFHAND ? offhand : slot < ARMOR_FROM || armour;
            out.add(keep ? slots.get(slot) : null);
        }
        return out;
    }

    /** Only the main storage, the slots a pickup fills; the rest blanked. */
    public static List<ItemView> storage(List<ItemView> slots) {
        var out = new ArrayList<ItemView>(slots.size());
        for (int slot = 0; slot < slots.size(); slot++) {
            out.add(slot < STORAGE_TO ? slots.get(slot) : null);
        }
        return out;
    }

    /** The number of empty storage slots (null entries among indexes {@code 0..35}). */
    public static int freeSlots(List<ItemView> storage) {
        int free = 0;
        for (int slot = 0; slot < Math.min(storage.size(), STORAGE_TO); slot++) {
            if (storage.get(slot) == null) {
                free++;
            }
        }
        return free;
    }

    /**
     * How many more of one item the slots can take: an empty slot holds a whole stack, and a matching
     * partial stack holds the rest of its stack. A stack that does not match takes nothing.
     *
     * @param storage the storage slots (see {@link #storage})
     * @param wanted which stacks are the same item
     * @param maxStack the item's maximum stack size
     */
    public static long room(List<ItemView> storage, Predicate<ItemView> wanted, int maxStack) {
        long room = 0;
        for (int slot = 0; slot < Math.min(storage.size(), STORAGE_TO); slot++) {
            ItemView view = storage.get(slot);
            if (view == null) {
                room += maxStack;
            } else if (wanted.test(view)) {
                room += Math.max(0, maxStack - view.amount());
            }
        }
        return room;
    }
}
