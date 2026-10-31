package dev.materione.plugin.gui.input;

import java.util.Locale;
import java.util.Optional;

/**
 * One operation a flow applies to an input slot's item ({@code gui.slot_item}, logic.md §4.1, §25): what the
 * CommandPanels {@code [itemaction]} covers. The item is the player's, so every operation is an explicit
 * step of a flow, and {@link #CLEAR} is the only one that deletes it.
 *
 * @param kind which operation
 * @param amount for {@link Kind#SET_AMOUNT}: the new amount, 0 clears the slot; otherwise unused
 * @param enchantment for {@link Kind#ENCHANT}: the enchantment's key, as Minecraft names it ({@code sharpness})
 * @param level for {@link Kind#ENCHANT}: the level, at least 1; otherwise unused
 */
public record SlotOp(Kind kind, int amount, String enchantment, int level) {

    /** The operations. */
    public enum Kind {
        /** Deletes the item. */
        CLEAR,
        /** Gives the item to the player (overflow drops at their feet) and empties the slot. */
        TAKE,
        /** Sets how many the stack holds; 0 clears the slot. */
        SET_AMOUNT,
        /** Restores the item's durability to full. */
        REPAIR,
        /** Adds an enchantment at a level. */
        ENCHANT;

        /** The value a flow writes in {@code op:}. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The operation an authored id names. */
        public static Optional<Kind> of(String id) {
            for (Kind kind : values()) {
                if (kind.id().equals(id)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** A clear, take or repair, which need no parameters. */
    public static SlotOp of(Kind kind) {
        return new SlotOp(kind, 0, "", 0);
    }
}
