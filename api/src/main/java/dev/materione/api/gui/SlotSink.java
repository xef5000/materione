package dev.materione.api.gui;

import dev.materione.api.NamespacedId;

/**
 * The slot sink a {@link RenderTarget#SLOT} layer places into (gui.md §4).
 * Slots are chest slots, 0-based and row-major ({@code 0 .. rows * 9 - 1}).
 * The item is a reference, resolved at apply time like a recipe result: a
 * Materione item ({@code shop:gui/main/sword}) or a vanilla material
 * ({@code minecraft:diamond_sword}).
 */
public interface SlotSink {

    /** Places {@code amount} of {@code item} in {@code slot}. */
    void place(int slot, NamespacedId item, int amount);
}
