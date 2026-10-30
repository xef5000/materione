package dev.materione.plugin.gui.input;

import dev.materione.plugin.gui.GuiMenu;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * What a GUI window hands its clicks to (logic.md §9 rule 1): the seam between the window holder, which
 * holds no policy, and the routing, which is Bukkit-free. {@link InventoryInput} is the production
 * implementation. Both methods run after the policy table has cancelled the event.
 */
public interface GuiInput {

    /** A click on one of the window's own slots. */
    void click(GuiMenu menu, InventoryClickEvent event);

    /** A click outside the window. */
    void outside(GuiMenu menu, InventoryClickEvent event);
}
