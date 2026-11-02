package dev.materione.platform.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** Sends a rich title to the existing native menu without closing it. */
public interface RichTitleTransport {
    boolean available();

    /** Returns an error when the title could not be sent; never replaces a menu. */
    String send(Player player, Inventory expected, Component title);
}
