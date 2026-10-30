package dev.materione.api.logic;

import java.util.Objects;

/**
 * Whether the server can run a type right now (logic.md §4.5). A type that needs a provider
 * (an economy, a permission plugin) reports {@code unavailable} while that provider is absent; the
 * editor greys it out and shows the reason, and running it fails safely rather than guessing.
 *
 * <p>Availability is dynamic: installing Vault later changes the answer without a reload, so it
 * is read from {@link ActionType#availability()} each time the vocabulary is built, never cached
 * by the registry.
 *
 * @param available true when the type can run
 * @param reason why not, when unavailable (blank when available)
 */
public record Availability(boolean available, String reason) {

    /** The common case. */
    public static final Availability AVAILABLE = new Availability(true, "");

    public Availability {
        reason = reason == null ? "" : reason;
        if (available) {
            reason = "";
        } else if (reason.isBlank()) {
            throw new IllegalArgumentException("an unavailable type must say why");
        }
    }

    /** Unavailable, with the sentence the editor shows ("No economy provider is installed"). */
    public static Availability unavailable(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new Availability(false, reason);
    }
}
