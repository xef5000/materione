package dev.materione.plugin.escrow.planted;

import dev.materione.plugin.escrow.runtime.Owner;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Fixture for the ban proof: a class that saves a player itself, asks an owner to save, and saves everyone. */
public final class PlantedSaver {

    public void violate(Player player, Owner<?> owner) {
        player.saveData();
        owner.save();
        Bukkit.savePlayers();
    }
}
