package dev.materione.api.mechanics;

import java.util.List;

/** Runtime wiring owned by a registration, independent of content reloads. */
public interface MechanicRuntime extends AutoCloseable {
    default List<MechanicListener<?>> listeners() {
        return List.of();
    }

    /** Release runtime state; managed listeners and context resources are closed by the host. */
    @Override
    default void close() {}
}
