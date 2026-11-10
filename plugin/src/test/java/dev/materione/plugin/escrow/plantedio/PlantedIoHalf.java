package dev.materione.plugin.escrow.plantedio;

import org.bukkit.entity.Player;

/** Fixture for the ban proof: an IO-side class that touches a Bukkit entity. */
public final class PlantedIoHalf {

    public String violate(Player player) {
        return player.getName();
    }
}
