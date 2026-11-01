package dev.materione.api.logic.provider;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Threading;
import java.util.Objects;

/**
 * The ownership and Folia support of a currency backend's read operations.
 *
 * <p>The owner is not itself proof of safety: an integration must explicitly establish that
 * {@code balance} and {@code has} are safe on Folia before reporting them available. In
 * particular, a global owner does not make an arbitrary third-party provider Folia-safe.
 *
 * @param owner the thread on which the backend may perform a read
 * @param folia whether the integration has established that the read is safe on Folia
 * @param offlineOwner the owner for offline reads, or null when offline reads are unsupported
 */
public record CurrencyReadCapability(Threading owner, Availability folia, Threading offlineOwner) {

    public CurrencyReadCapability {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(folia, "folia");
        if (offlineOwner == Threading.PLAYER_REGION) {
            throw new IllegalArgumentException("offline reads cannot require a player region");
        }
    }

    /** A provider has not declared a verified Folia read capability. */
    public static CurrencyReadCapability unsupported(Threading owner) {
        return new CurrencyReadCapability(owner,
                Availability.unavailable("the currency integration has not declared Folia-safe reads"), null);
    }
}
