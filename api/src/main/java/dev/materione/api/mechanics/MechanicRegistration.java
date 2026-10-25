package dev.materione.api.mechanics;

import dev.materione.api.NamespacedId;

/** Ownership handle; closing is idempotent and removes runtime wiring immediately. */
public interface MechanicRegistration extends AutoCloseable {
    NamespacedId id();

    @Override
    void close();
}
