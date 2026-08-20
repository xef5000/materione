package dev.materione.api.registry;

/**
 * Thrown by {@link Registry#register} on misuse: duplicate id, reserved
 * {@code minecraft} namespace, invalid id, or registration outside an open
 * window. Registration failures are developer errors, not content errors —
 * content errors are diagnostics (plugin architecture §5), registry misuse
 * fails fast with a precise message.
 */
public class RegistryException extends RuntimeException {

    public RegistryException(String message) {
        super(message);
    }
}
