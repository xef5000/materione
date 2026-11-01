package dev.materione.api.logic;

/** A pending owner-bound read was dropped because its entity retired. */
public final class ReadRetiredException extends ReadUnavailableException {
    public ReadRetiredException(String reason) {
        super(reason);
    }
}
