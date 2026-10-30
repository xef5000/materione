package dev.materione.api.logic.provider;

import java.util.Objects;

/**
 * The outcome of a permission or transfer request (logic.md §4.5): done, or refused with a reason
 * an author can read in a trace.
 *
 * @param ok true when the provider accepted the request
 * @param reason why it did not, when refused (blank when {@code ok})
 */
public record ProviderResult(boolean ok, String reason) {

    /** The request was accepted. */
    public static final ProviderResult OK = new ProviderResult(true, "");

    public ProviderResult {
        Objects.requireNonNull(reason, "reason");
        if (ok) {
            reason = "";
        } else if (reason.isBlank()) {
            throw new IllegalArgumentException("a refused provider request must say why");
        }
    }

    /** The request was refused for {@code reason}. */
    public static ProviderResult failed(String reason) {
        return new ProviderResult(false, reason);
    }
}
