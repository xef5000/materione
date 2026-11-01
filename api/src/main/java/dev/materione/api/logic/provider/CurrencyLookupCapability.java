package dev.materione.api.logic.provider;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.Threading;
import java.util.Objects;

/**
 * The owner and demonstrated Folia safety of binding a currency and calling its backend's
 * availability. Retrieving this immutable declaration must itself be safe on any thread.
 */
public record CurrencyLookupCapability(Threading owner, Availability folia) {
    public CurrencyLookupCapability {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(folia, "folia");
    }

    /** Existing integrations are not presumed safe for Folia lookups. */
    public static CurrencyLookupCapability unsupported() {
        return new CurrencyLookupCapability(Threading.GLOBAL,
                Availability.unavailable("currency lookup has not declared Folia safety"));
    }
}
