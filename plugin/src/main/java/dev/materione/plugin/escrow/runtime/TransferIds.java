package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.model.Id128;
import java.util.UUID;

/** Adapter between the deterministic 128-bit ledger id and Bukkit's UUID. */
final class TransferIds {

    private TransferIds() {}

    static Id128 of(UUID id) {
        return new Id128(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    static UUID uuid(Id128 id) {
        return new UUID(id.hi(), id.lo());
    }
}
