package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class JointTransferPropertyTest {

    @Test
    void everyKillBoundaryConservesExactlyOneStackAndNeverDuplicatesIt() {
        for (int seed = 0; seed < 250; seed++) {
            Random random = new Random(seed);
            Id128 sourceId = id(random);
            Id128 txn = id(random);
            Id128 destinationId = id(random);
            StackBytes stack = StackBytes.of(randomBytes(random));
            TransferHolder sourceHolder =
                    new TransferHolder(id(random), TransferHolder.Kind.PLAYER, "");
            TransferHolder destinationHolder =
                    new TransferHolder(id(random), TransferHolder.Kind.KEYED, "vault:" + (seed % 7));
            Entry held = Entry.held(sourceId, stack, "origin", seed % 53, seed);
            Account initialSource = new Account(1, List.of(held));
            Account initialDestination = Account.EMPTY;

            Account source = initialSource;
            Account destination = initialDestination;
            Ledger ledger = Ledger.EMPTY;
            for (int boundary = 0; boundary <= 7; boundary++) {
                Snapshot crash = snapshot(source, destination, ledger, sourceHolder, destinationHolder);
                assertThat(crash.count()).as("seed %s boundary %s", seed, boundary).isEqualTo(1);

                switch (boundary) {
                    case 0 -> source = applied(JointTransfer.prepareSource(source, sourceId, txn));
                    case 1 -> destination = applied(JointTransfer.reserve(
                            destination, held, destinationId, txn, "transfer", 1));
                    case 2 -> ledger = new Ledger(List.of(new LedgerRecord.TransferIntent(
                            txn, sourceHolder, sourceId, source.gen(), destinationHolder,
                            destinationId, destination.gen(), stack.hash16(), 1, seed)));
                    case 3 -> source = applied(JointTransfer.commitSource(
                            source, ledger, txn, sourceId));
                    case 4 -> destination = applied(JointTransfer.commitDestination(
                            destination, ledger, txn, destinationId, true));
                    case 5 -> ledger = ledger.appended(new LedgerRecord.TransferDone(txn));
                    case 6 -> {
                        destination = applied(JointTransfer.commitDestination(
                                destination, ledger, txn, destinationId, true));
                        source = applied(JointTransfer.commitSource(source, ledger, txn, sourceId));
                    }
                    case 7 -> { }
                }
            }
        }
    }

    private static Snapshot snapshot(Account source, Account destination, Ledger ledger,
            TransferHolder sourceHolder, TransferHolder destinationHolder) {
        List<StackBytes> returned = new ArrayList<>();
        Inventory inventory = new Inventory() {
            @Override
            public boolean remove(StackBytes stack) {
                return false;
            }

            @Override
            public boolean tryAdd(StackBytes stack) {
                returned.add(stack);
                return true;
            }
        };
        Account recoveredSource = JointTransfer.recovered(source, ledger, sourceHolder, inventory);
        Account recoveredDestination = JointTransfer.recovered(destination, ledger, destinationHolder, inventory);
        int count = returned.size();
        for (Entry entry : recoveredSource.entries()) {
            if (entry.state() == EntryState.HELD || entry.state() == EntryState.OWED) {
                count++;
            }
        }
        for (Entry entry : recoveredDestination.entries()) {
            if (entry.state() == EntryState.HELD || entry.state() == EntryState.OWED) {
                count++;
            }
        }
        return new Snapshot(count);
    }

    private static Account applied(Step step) {
        if (step instanceof Step.Applied applied) {
            return applied.account();
        }
        if (step instanceof Step.NoOp noOp) {
            return noOp.account();
        }
        throw new AssertionError("unexpected refusal: " + step);
    }

    private static Id128 id(Random random) {
        long hi = random.nextLong();
        long lo = random.nextLong() | 1L;
        Id128 id = new Id128(hi, lo);
        return id.isZero() ? new Id128(1, 1) : id;
    }

    private static byte[] randomBytes(Random random) {
        byte[] bytes = new byte[16 + random.nextInt(64)];
        random.nextBytes(bytes);
        return bytes;
    }

    private record Snapshot(int count) {}
}
