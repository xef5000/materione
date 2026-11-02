package dev.materione.integrations.variables;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.provider.EconomyResult;
import java.util.Collection;
import java.util.UUID;

/**
 * Live currency access for synchronous placeholder requests. The implementation must re-resolve
 * the exact currency and backend at each use, and return a failure rather than wait for a thread
 * hop or access player, binding, backend, or availability data without proof of the required
 * owner. Immutable declared-id enumeration is safe on any thread. These rules also apply to
 * Paper async requests and to offline identities.
 */
public interface CurrencyPlaceholderBridge {

    /** Current declared qualified currency ids, including currencies whose provider is unavailable. */
    Collection<NamespacedId> currencies();

    /**
     * Read this exact currency and, when requested, format the result using the existing formatter
     * against the same immutable binding and metadata. Fail if that consistency cannot be proved
     * across a replacement; never mix a balance with a later currency definition.
     */
    Balance read(NamespacedId currency, UUID viewer, boolean formatted);

    /** A provider outcome and, only for a successful formatted request, its formatted text. */
    record Balance(EconomyResult result, String formatted) { }
}
