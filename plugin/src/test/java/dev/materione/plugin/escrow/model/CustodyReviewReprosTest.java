package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustodyReviewReprosTest {

    private static final Id128 OWNER = new Id128(1, 1);
    private static final Id128 OTHER = new Id128(2, 2);
    private static final TransferHolder SOURCE =
            new TransferHolder(OWNER, TransferHolder.Kind.KEYED, "source");
    private static final TransferHolder DESTINATION =
            new TransferHolder(OTHER, TransferHolder.Kind.KEYED, "destination");
    private static final Id128 TXN = new Id128(10, 10);
    private static final Id128 SOURCE_ENTRY = new Id128(11, 11);
    private static final Id128 RESERVATION = new Id128(12, 12);
    private static final StackBytes STACK = StackBytes.of(new byte[] {1, 2, 3});
    private static final Inventory ALWAYS_ADDS = new Inventory() {
        @Override
        public boolean remove(StackBytes stack) {
            return true;
        }

        @Override
        public boolean tryAdd(StackBytes stack) {
            return true;
        }
    };

    @Test
    void anOlderHeldSourceSnapshotIsNotRolledForwardByTheJointIntent() {
        Account oldSource = new Account(4, List.of(held()));
        Step.Applied preparedSource = (Step.Applied) JointTransfer.prepareSource(oldSource, SOURCE_ENTRY, TXN);
        Step.Applied preparedDestination = (Step.Applied) JointTransfer.reserve(
                Account.EMPTY, held(), RESERVATION, TXN, "transfer", 0);
        Ledger decided = new Ledger(List.of(intent(preparedSource.account(), preparedDestination.account())));
        Account committedDestination = ((Step.Applied) JointTransfer.commitDestination(
                preparedDestination.account(), decided, TXN, RESERVATION, true)).account();

        Account recoveredSource = JointTransfer.recovered(oldSource, decided, SOURCE, ALWAYS_ADDS);

        assertThat(recoveredSource.entries()).as("the source id named by INTENT must not survive")
                .isEmpty();
        assertThat(committedDestination.entries()).hasSize(1);
    }

    @Test
    void aDestinationSnapshotFromBeforeReservationLosesTheItemAfterCommit() {
        Account oldSource = new Account(4, List.of(held()));
        Step.Applied preparedSource = (Step.Applied) JointTransfer.prepareSource(oldSource, SOURCE_ENTRY, TXN);
        Step.Applied preparedDestination = (Step.Applied) JointTransfer.reserve(
                Account.EMPTY, held(), RESERVATION, TXN, "transfer", 0);
        Ledger decided = new Ledger(List.of(intent(preparedSource.account(), preparedDestination.account())));
        Account committedSource = ((Step.Applied) JointTransfer.commitSource(
                preparedSource.account(), decided, TXN, SOURCE_ENTRY)).account();

        Account recoveredSource = JointTransfer.recovered(committedSource, decided, SOURCE, ALWAYS_ADDS);
        Account recoveredDestination = JointTransfer.recovered(Account.EMPTY, decided, DESTINATION, ALWAYS_ADDS);

        assertThat(recoveredSource.entries()).isEmpty();
        assertThat(recoveredDestination.entries()).as("the decided reservation must have one final owner")
                .hasSize(1);
    }

    @Test
    void anUndecidedKeyedSourceReturnsToItsKeyedAccountNotThePlayerInventory() {
        List<StackBytes> returned = new ArrayList<>();
        Inventory inventory = new Inventory() {
            @Override
            public boolean remove(StackBytes stack) {
                return true;
            }

            @Override
            public boolean tryAdd(StackBytes stack) {
                returned.add(stack);
                return true;
            }
        };
        Account old = new Account(4, List.of(held()));
        Account transferring = ((Step.Applied) JointTransfer.prepareSource(
                old, SOURCE_ENTRY, TXN)).account();

        Account recovered = JointTransfer.recovered(transferring, Ledger.EMPTY, SOURCE, inventory);

        assertThat(returned).as("a keyed holder is not the player inventory").isEmpty();
        assertThat(recovered.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.state()).isEqualTo(EntryState.HELD));
    }

    @Test
    void aReservationCannotOccupyTheSameDestinationSlotAsAnExistingEntry() {
        Account occupied = new Account(1, List.of(Entry.held(
                new Id128(20, 20), StackBytes.of(new byte[] {9}), "vault", 0, 1L)));

        Step step = JointTransfer.reserve(
                occupied, held(), RESERVATION, TXN, "custody.transfer", 0);

        assertThat(step).as("the occupied slot should refuse and preserve the destination")
                .isInstanceOf(Step.Refused.class);
    }

    private static Entry held() {
        return Entry.held(SOURCE_ENTRY, STACK, "source", 0, 1L);
    }

    private static LedgerRecord.TransferIntent intent(Account preparedSource, Account preparedDestination) {
        return new LedgerRecord.TransferIntent(TXN, SOURCE, SOURCE_ENTRY, preparedSource.gen(),
                DESTINATION, RESERVATION, preparedDestination.gen(), 0, STACK, 1L, 2L);
    }
}
