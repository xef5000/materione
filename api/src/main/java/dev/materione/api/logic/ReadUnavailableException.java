package dev.materione.api.logic;

/**
 * A live read cannot be performed safely on the calling thread. This is distinct from an absent
 * value: callers must not replace it with a variable default or negate it into a successful check.
 */
public class ReadUnavailableException extends IllegalStateException {
    public ReadUnavailableException(String reason) {
        super(reason);
    }
}
