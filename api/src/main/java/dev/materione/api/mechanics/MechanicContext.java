package dev.materione.api.mechanics;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.scheduling.SchedulingGateway;
import org.bukkit.plugin.Plugin;

/** Services and resource ownership for one registered mechanic runtime. */
public interface MechanicContext {
    /** The registering plugin; use it as the owner of scheduled work. */
    Plugin plugin();

    DiagnosticsBus diagnostics();

    SchedulingGateway scheduling();

    /** Captures the current bindings; listeners receive this automatically at entry. */
    MechanicLookup snapshot();

    /**
     * Own a subscription, timer cancellation handle, or other resource. Closed
     * in reverse order on unregister, owner disable, or failed initialization.
     */
    <T extends AutoCloseable> T track(T resource);
}
