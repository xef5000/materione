package dev.materione.api.logic;

import dev.materione.api.NamespacedId;

/**
 * Ownership handle for one registered logic type; closing is idempotent and removes the type from
 * its registry at once. Content that already loaded keeps the parsed steps it holds until the next
 * reload; the removed type is then reported as unknown where it is used.
 */
public interface LogicRegistration extends AutoCloseable {

    /** The full id the type is registered under. */
    NamespacedId id();

    @Override
    void close();
}
