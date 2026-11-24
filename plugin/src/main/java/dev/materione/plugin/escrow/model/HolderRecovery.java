package dev.materione.plugin.escrow.model;

import java.util.ArrayList;
import java.util.List;

/** Recovery of a parked keyed account without returning its contents to the player's inventory. */
public final class HolderRecovery {

    private HolderRecovery() {}

    /** Applies decided consume entries and restores undecided EXITING entries as HELD, idempotently. */
    public static Account recoverConsumes(Account account, Ledger decisions) {
        List<Entry> next = new ArrayList<>();
        boolean changed = false;
        for (Entry entry : account.entries()) {
            if (entry.state() != EntryState.EXITING) {
                next.add(entry);
            } else if (decisions.hasIntent(entry.id())) {
                changed = true;
            } else {
                next.add(entry.held());
                changed = true;
            }
        }
        return changed ? account.next(next) : account;
    }
}
