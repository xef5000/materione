package dev.materione.web.relay;

/** Relay API failure carrying the worker's status and error code (if any). */
public final class RelayException extends Exception {

    private final int status;
    private final String code;

    public RelayException(String message) {
        this(message, 0, null, null);
    }

    public RelayException(String message, int status, String code, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String errorCode() {
        return code;
    }

    /** Quota and rate-limit responses the caller should back off from. */
    public boolean transientOverload() {
        return status == 429 || status == 503 || status == 502;
    }
}
