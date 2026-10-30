package dev.materione.plugin.logic.planted;

import org.bukkit.event.EventHandler;

/** Fixture for the R10 proof: the legitimate shape, a listener that depends on nothing it must not. */
public final class PlantedAdapter {

    @EventHandler
    public String onSomething() {
        return "handed to a collaborator";
    }
}
