package dev.materione.plugin.escrow.io;

import static dev.materione.plugin.escrow.io.LedgerRigTest.id;
import static dev.materione.plugin.escrow.io.LedgerRigTest.intent;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.Inventory;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.Recovery;
import dev.materione.plugin.escrow.model.StackBytes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The recovery input the join handler gets from the preloaded state (§5.7): no IO, the model's recovery. */
class LedgerStateRecoveryTest {

    private static StackBytes stack(String name) {
        return StackBytes.of(name.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Bag implements Inventory {
        final List<StackBytes> stacks = new ArrayList<>();

        @Override
        public boolean remove(StackBytes stack) {
            return stacks.remove(stack);
        }

        @Override
        public boolean tryAdd(StackBytes stack) {
            return stacks.add(stack);
        }
    }

    private static final Account ACCOUNT = new Account(7, List.of(
            Entry.held(id(1001), stack("trashed"), "shop", 3, 10L),
            Entry.held(id(1002), stack("kept"), "shop", 4, 11L)));

    @Test
    void aReadyStateDropsTheDecidedEntryAndReturnsTheRest() {
        LedgerState ready = new LedgerState.Ready(new Ledger(List.of(intent(1, 5L))));
        Bag inventory = new Bag();
        Recovery.Result result = ready.recover(new AccountRead.Readable(ACCOUNT), inventory);
        assertThat(result).isInstanceOfSatisfying(Recovery.Result.Recovered.class, recovered -> {
            assertThat(recovered.dropped()).extracting(Entry::id).containsExactly(id(1001));
            assertThat(recovered.returned()).containsExactly(stack("kept"));
        });
        assertThat(inventory.stacks).containsExactly(stack("kept"));
    }

    @Test
    void anUnrecoverableLedgerTouchesNothingAndSaysWhy() {
        LedgerState bad = new LedgerState.Unrecoverable(LedgerState.Why.CORRUPT, "x.ledger.corrupt-1", "corrupt at byte 9");
        Bag inventory = new Bag();
        Recovery.Result result = bad.recover(new AccountRead.Readable(ACCOUNT), inventory);
        assertThat(result).isInstanceOfSatisfying(Recovery.Result.Unrecoverable.class, unrecoverable -> {
            assertThat(unrecoverable.cause()).isEqualTo(Recovery.Cause.LEDGER);
            assertThat(unrecoverable.detail()).contains("CORRUPT").contains("corrupt at byte 9");
        });
        assertThat(inventory.stacks).isEmpty();
    }

    @Test
    void anUnreadableAccountIsReportedFirst() {
        LedgerState ready = new LedgerState.Ready(Ledger.EMPTY);
        AccountRead bad = new AccountRead.Unreadable(AccountRead.Reason.BAD_CRC, new byte[] {1});
        assertThat(ready.recover(bad, new Bag())).isInstanceOfSatisfying(Recovery.Result.Unrecoverable.class,
                u -> assertThat(u.cause()).isEqualTo(Recovery.Cause.ACCOUNT));
        LedgerState broken = new LedgerState.Unrecoverable(LedgerState.Why.NEWER_VERSION, "f", "d");
        assertThat(broken.recover(bad, new Bag())).isInstanceOfSatisfying(Recovery.Result.Unrecoverable.class,
                u -> assertThat(u.cause()).isEqualTo(Recovery.Cause.ACCOUNT));
    }
}
