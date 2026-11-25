package dev.materione.plugin.escrow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.io.AppendResult;
import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import dev.materione.plugin.escrow.io.LedgerOptions;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.LedgerRecord;
import dev.materione.plugin.escrow.model.StackBytes;
import dev.materione.plugin.escrow.model.TransferHolder;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustodyTransferIdempotencyTest {

    @TempDir
    Path root;

    @Test
    void aReusedTransactionCannotConfirmADifferentTransfer() {
        var source = new TransferHolder(new Id128(1, 1), TransferHolder.Kind.PLAYER, "");
        var destination = new TransferHolder(new Id128(2, 2), TransferHolder.Kind.PLAYER, "");
        var otherDestination = new TransferHolder(new Id128(3, 3), TransferHolder.Kind.KEYED, "vault:3");
        var txn = new Id128(10, 10);
        var sourceEntry = new Id128(20, 20);
        var reservation = new Id128(30, 30);
        try (EscrowLedgerStore ledger = new EscrowLedgerStore(
                root, Runnable::run, LedgerOptions.DEFAULTS.withFsync(false), () -> 1L,
                diagnostic -> { }, ignored -> { })) {
            AppendResult appended = ledger.append(CustodyTransfer.SHARED_LEDGER, new LedgerRecord.TransferIntent(
                    txn, source, sourceEntry, 1L, destination, reservation, 1L,
                    new Id128(40, 40), 1, 1L)).join();
            assertThat(appended).isInstanceOf(AppendResult.Durable.class);
            CustodyTransfer transfers = new CustodyTransfer(
                    null, ledger, null, () -> new Id128(50, 50), () -> 2L);

            CustodyTransfer.Outcome outcome = transfers.transfer(new CustodyTransfer.Request(
                    txn, source, sourceEntry, otherDestination, 7, CustodyTransfer.Reach.DECIDED))
                    .toCompletableFuture().join();

            assertThat(outcome).isInstanceOf(CustodyTransfer.Outcome.Refused.class);
        }
    }

    @Test
    void theDurableStoreComparesTheWholeTransferIdentityBeforeReusingARecord() {
        var source = new TransferHolder(new Id128(1, 1), TransferHolder.Kind.PLAYER, "");
        var destination = new TransferHolder(new Id128(2, 2), TransferHolder.Kind.KEYED, "vault:2");
        var txn = new Id128(10, 10);
        StackBytes stack = StackBytes.of(new byte[] {4, 5});
        var first = new LedgerRecord.TransferIntent(txn, source, new Id128(20, 20), 1,
                destination, new Id128(30, 30), 1, 7, stack, 2, 1);
        try (EscrowLedgerStore ledger = new EscrowLedgerStore(
                root, Runnable::run, LedgerOptions.DEFAULTS.withFsync(false), () -> 1L,
                diagnostic -> { }, ignored -> { })) {
            assertThat(ledger.append(CustodyTransfer.SHARED_LEDGER, first).join())
                    .isInstanceOf(AppendResult.Durable.class);
            for (LedgerRecord.TransferIntent mismatch : java.util.List.of(
                    new LedgerRecord.TransferIntent(txn, source, new Id128(20, 20), 1,
                            destination, new Id128(31, 31), 1, 7, stack, 2, 2),
                    new LedgerRecord.TransferIntent(txn, source, new Id128(20, 20), 1,
                            destination, new Id128(30, 30), 1, 7,
                            StackBytes.of(new byte[] {4, 6}), 2, 3),
                    new LedgerRecord.TransferIntent(txn, source, new Id128(20, 20), 1,
                            destination, new Id128(30, 30), 1, 7, stack, 3, 4))) {
                assertThat(ledger.append(CustodyTransfer.SHARED_LEDGER, mismatch).join())
                        .isInstanceOfSatisfying(AppendResult.Refused.class, refused ->
                                assertThat(refused.reason()).isEqualTo(AppendResult.Reason.IDENTITY_MISMATCH));
            }
            assertThat(ledger.preloaded(CustodyTransfer.SHARED_LEDGER)
                    .orElseThrow()).isInstanceOfSatisfying(
                            dev.materione.plugin.escrow.io.LedgerState.Ready.class,
                            ready -> assertThat(ready.ledger().records()).containsExactly(first));
            assertThat(ledger.append(CustodyTransfer.SHARED_LEDGER, first).join())
                    .isInstanceOf(AppendResult.Durable.class);
        }
    }
}
