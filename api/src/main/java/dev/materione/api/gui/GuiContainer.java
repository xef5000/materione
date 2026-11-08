package dev.materione.api.gui;

import java.util.List;
import java.util.Objects;

/**
 * One container profile (gui.md §12.5.1): the geometry of a screen the GUI is drawn in and the slots it offers.
 * Profiles are data, read from {@code shared/gui/containers.json} by the compiler, the runtime and the studio
 * alike; nothing in code is specific to one container. The chest is six entries of it.
 *
 * <p>Slot {@code i} is drawn at {@code (slotX + 18 * (i % columns), slotY + 18 * (i / columns))}, the pitch being
 * the vanilla slot's 18 px.
 *
 * @param id the {@code container:} value (lower-snake-case)
 * @param label the studio's name for it
 * @param group the heading the studio's select groups it under
 * @param slotCount the container's slots, numbered {@code 0..slotCount-1}
 * @param columns the width of the slot grid
 * @param slotX the screen x of slot 0
 * @param slotY the screen y of slot 0
 * @param width the screen width in GUI pixels
 * @param height the screen height in GUI pixels
 * @param inventoryX the screen x of the player's first inventory slot (the 27 main slots; the hotbar is 58 px below)
 * @param inventoryY the screen y of the player's first inventory slot
 * @param inventoryTop the first row of the player-inventory art (where the veil starts): {@code inventoryY - 14}
 * @param titleCentred whether the client centres the title (the dispenser's screen); when true {@code titleX} is unused
 * @param titleX the pen origin of the title
 * @param titleY the row of the title's label
 * @param inventoryLabelX the screen x of the vanilla "Inventory" label
 * @param inventoryLabelY the screen y of the vanilla "Inventory" label
 * @param underlay the blits that compose the vanilla screen, in draw order
 * @param open how the window is created
 */
public record GuiContainer(
        String id, String label, String group, int slotCount, int columns, int slotX, int slotY,
        int width, int height, int inventoryX, int inventoryY, int inventoryTop,
        boolean titleCentred, int titleX, int titleY, int inventoryLabelX, int inventoryLabelY,
        List<Blit> underlay, Open open) {

    /** The vanilla slot pitch, in pixels. */
    public static final int PITCH = 18;

    public GuiContainer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(open, "open");
        underlay = List.copyOf(underlay);
        if (slotCount < 1 || columns < 1 || slotCount % columns != 0) {
            throw new IllegalArgumentException(id + ": slotCount must be a positive multiple of columns");
        }
    }

    /** The screen x of slot {@code slot}'s top-left corner. */
    public int slotX(int slot) {
        return slotX + PITCH * (slot % columns);
    }

    /** The screen y of slot {@code slot}'s top-left corner. */
    public int slotY(int slot) {
        return slotY + PITCH * (slot / columns);
    }

    /**
     * A blit of one sheet region onto the screen.
     *
     * @param sheet the background sheet's name
     * @param srcX the source rectangle's x on the sheet
     * @param srcY the source rectangle's y on the sheet
     * @param srcWidth the source rectangle's width
     * @param srcHeight the source rectangle's height
     * @param dstX the destination x on the screen
     * @param dstY the destination y on the screen
     */
    public record Blit(String sheet, int srcX, int srcY, int srcWidth, int srcHeight, int dstX, int dstY) {

        public Blit {
            Objects.requireNonNull(sheet, "sheet");
        }
    }

    /**
     * How a window of this container is created: by size (the legacy chest path, a size of 9 to 54 in steps of 9) or
     * by the {@code InventoryType} name (gui.md §12.6). Exactly one of the two is set.
     *
     * @param size the window size in slots, or 0 when the window is made by type
     * @param inventoryType the {@code InventoryType} name, or null when the window is made by size
     */
    public record Open(int size, String inventoryType) {

        public Open {
            if ((size == 0) == (inventoryType == null)) {
                throw new IllegalArgumentException("open is either a size or an inventory type, not both or neither");
            }
        }

        /** A window of {@code size} slots. */
        public static Open bySize(int size) {
            return new Open(size, null);
        }

        /** A window made from the Bukkit {@code InventoryType} named {@code type}. */
        public static Open byType(String type) {
            return new Open(0, Objects.requireNonNull(type, "type"));
        }
    }
}
