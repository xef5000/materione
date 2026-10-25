package dev.materione.api.mechanics;

import dev.materione.api.NamespacedId;
import org.bukkit.plugin.Plugin;

/** Executable mechanic extensions; registration never edits or reloads content. */
public interface MechanicsApi {
    /**
     * Opens one bounded registration window for this entry. Call on the
     * global/main thread during plugin enable or via the scheduling gateway.
     * Late registration takes effect for content on the next normal reload.
     * Duplicate/reserved ids fail explicitly; owner disable closes the handle.
     */
    MechanicRegistration register(Plugin owner, NamespacedId id, Mechanic mechanic);
}
