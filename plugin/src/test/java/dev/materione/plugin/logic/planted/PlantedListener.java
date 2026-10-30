package dev.materione.plugin.logic.planted;

import org.bukkit.event.EventHandler;

/** Fixture for the R10 proof: a listener (it handles events) that reaches a collaborator it must not. */
public final class PlantedListener {

    /** The violation: the handler runs an action directly instead of handing the event on. */
    @EventHandler
    public Object onSomething() {
        return new PlantedFirst().parse();
    }
}
