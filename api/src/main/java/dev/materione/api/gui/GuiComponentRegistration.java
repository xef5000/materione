package dev.materione.api.gui;

import dev.materione.api.NamespacedId;

/** Ownership handle for one registered GUI component; closing is idempotent. */
public interface GuiComponentRegistration extends AutoCloseable {

    NamespacedId id();

    @Override
    void close();
}
