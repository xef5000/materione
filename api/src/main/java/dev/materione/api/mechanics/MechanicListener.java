package dev.materione.api.mechanics;

import java.util.Objects;
import java.util.function.BiConsumer;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

/**
 * A managed native event subscription. The host registers it once, supplies
 * an event-entry lookup, isolates handler failures and unregisters it on close.
 * Synchronous events run on their existing owning region/entity thread.
 */
public record MechanicListener<E extends Event>(
        Class<E> eventType,
        EventPriority priority,
        boolean ignoreCancelled,
        ThreadClass thread,
        BiConsumer<E, MechanicLookup> handler) {

    public MechanicListener {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(handler, "handler");
    }

    public enum ThreadClass {
        REGION,
        ASYNC
    }
}
