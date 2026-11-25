package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JointTransferTest {

    private static final Id128 OWNER_A = new Id128(1, 1);
    private static final Id128 OWNER_B = new Id128(2, 2);
    private static final TransferHolder SOURCE = new TransferHolder(OWNER_A, TransferHolder.Kind.PLAYER, "");
    private static final TransferHolder DESTINATION =
            new TransferHolder(OWNER_B, TransferHolder.Kind.KEYED, "vault:2");
    private static final Id128 TXN = new Id128(10, 20);
    private static final Id128 SOURCE_ENTRY = new Id128(30, 40);
    private static final Id128 RESERVATION = new Id128(50, 60);
    private static final StackBytes STACK = StackBytes.of(new byte[] {1, 2, 3, 4});

    private final List<StackBytes> returned = new ArrayList<>();
    private final Inventory inventory = new Inventory() {
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

    @Test
    void everyPreDecisionBoundaryRollsBackToTheSourceAndDropsTheReservation() {
        Prepared prepared = prepared();
        Account sourceAfter = JointTransfer.recovered(prepared.source(), Ledger.EMPTY, SOURCE, inventory);
        Account destinationAfter =
                JointTransfer.recovered(prepared.destination(), Ledger.EMPTY, DESTINATION, inventory);

        assertThat(sourceAfter.entries()).isEmpty();
        assertThat(returned).containsExactly(STACK);
        assertThat(destinationAfter.entries()).isEmpty();
        assertThat(JointTransfer.recovered(sourceAfter, Ledger.EMPTY, SOURCE, inventory)).isEqualTo(sourceAfter);
        assertThat(returned).containsExactly(STACK);
    }

    @Test
    void preparedSourceAndReservationUseRoundTrippableTransactionalAccountStates() {
        Prepared prepared = prepared();
        assertThat(AccountCodec.decode(AccountCodec.encode(prepared.source())))
                .isEqualTo(new AccountRead.Readable(prepared.source()));
        assertThat(AccountCodec.decode(AccountCodec.encode(prepared.destination())))
                .isEqualTo(new AccountRead.Readable(prepared.destination()));
        assertThat(prepared.source().entries().get(0).state()).isEqualTo(EntryState.TRANSFERRING);
        assertThat(prepared.destination().entries().get(0).state()).isEqualTo(EntryState.RESERVED);
    }

    @Test
    void everyPostDecisionBoundaryCommitsEachSideOnlyOnce() {
        Prepared prepared = prepared();
        Ledger decision = new Ledger(List.of(intent(prepared)));

        Account source = JointTransfer.recovered(prepared.source(), decision, SOURCE, inventory);
        Account destination = JointTransfer.recovered(prepared.destination(), decision, DESTINATION, inventory);
        assertThat(source.entries()).isEmpty();
        assertThat(destination.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(RESERVATION);
            assertThat(entry.state()).isEqualTo(EntryState.HELD);
        });
        assertThat(returned).isEmpty();

        Account sourceAgain = JointTransfer.recovered(source, decision, SOURCE, inventory);
        Account destinationAgain = JointTransfer.recovered(destination, decision, DESTINATION, inventory);
        assertThat(sourceAgain).isEqualTo(source);
        assertThat(destinationAgain).isEqualTo(destination);
        assertThat(returned).isEmpty();
    }

    @Test
    void aCrashAfterOnlyOneOwnerCommitIsCompletedByRecoveryWithoutDuplication() {
        Prepared prepared = prepared();
        Ledger decision = new Ledger(List.of(intent(prepared)));

        Step.Applied sourceCommit = (Step.Applied) JointTransfer.commitSource(
                prepared.source(), decision, TXN, SOURCE_ENTRY);
        Account replayedDestination =
                JointTransfer.recovered(prepared.destination(), decision, DESTINATION, inventory);

        assertThat(sourceCommit.account().entries()).isEmpty();
        assertThat(replayedDestination.entries()).extracting(Entry::id).containsExactly(RESERVATION);
        assertThat(returned).isEmpty();
    }

    @Test
    void recoveryDoesNotResurrectADestinationThatANewerSnapshotAlreadyRemoved() {
        Prepared prepared = prepared();
        Ledger decision = new Ledger(List.of(intent(prepared)));
        Account committed = ((Step.Applied) JointTransfer.commitDestination(
                prepared.destination(), decision, TXN, RESERVATION, true)).account();
        committed = new Account(committed.gen() + 1, List.of());

        Account recovered = JointTransfer.recovered(committed, decision, DESTINATION, inventory);

        assertThat(recovered.entries()).isEmpty();
    }

    @Test
    void deathDoesNotReleaseEitherTransferPreparation() {
        Prepared prepared = prepared();

        Step source = EscrowTransfer.death(prepared.source(), Ledger.EMPTY, inventory, false);
        Step destination = EscrowTransfer.death(prepared.destination(), Ledger.EMPTY, inventory, false);

        assertThat(source).isInstanceOf(Step.NoOp.class);
        assertThat(destination).isInstanceOf(Step.NoOp.class);
        assertThat(returned).isEmpty();
    }

    @Test
    void aKeyedDestinationCannotReserveAnOccupiedSlot() {
        Entry occupied = Entry.held(new Id128(70, 80), StackBytes.of(new byte[] {9}),
                "vault", 4, 999);
        Account destination = new Account(1, List.of(occupied));
        Entry source = Entry.held(SOURCE_ENTRY, STACK, "origin", 0, 123);

        Step reserved = JointTransfer.reserve(
                destination, source, RESERVATION, TXN, "custody.transfer", 4);

        assertThat(reserved).isInstanceOf(Step.Refused.class);
    }

    @Test
    void keyedConsumeRecoveryKeepsUndecidedEntriesAndDropsOnlyItsDurableIntent() {
        Entry held = Entry.held(SOURCE_ENTRY, STACK, "vault", 0, 1);
        Account frozen = new Account(2, List.of(held.exiting(TXN)));

        Account undecided = HolderRecovery.recoverConsumes(frozen, Ledger.EMPTY);
        assertThat(undecided.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.state()).isEqualTo(EntryState.HELD));

        Ledger decided = new Ledger(List.of(new LedgerRecord.Intent(
                TXN, SOURCE_ENTRY, ExitKind.CONSUME, STACK.hash16(), 3, 2)));
        Account consumed = HolderRecovery.recoverConsumes(frozen, decided);
        assertThat(consumed.entries()).isEmpty();
    }

    private Prepared prepared() {
        Entry sourceEntry = Entry.held(SOURCE_ENTRY, STACK, "trade:window", 4, 123);
        Account source = new Account(7, List.of(sourceEntry));
        Step.Applied frozen = (Step.Applied) JointTransfer.prepareSource(source, SOURCE_ENTRY, TXN);
        Step.Applied reserved = (Step.Applied) JointTransfer.reserve(
                Account.EMPTY, sourceEntry, RESERVATION, TXN, "custody.transfer", 0);
        return new Prepared(frozen.account(), reserved.account());
    }

    private static LedgerRecord.TransferIntent intent(Prepared prepared) {
        return new LedgerRecord.TransferIntent(TXN, SOURCE, SOURCE_ENTRY, prepared.source().gen(),
                DESTINATION, RESERVATION, prepared.destination().gen(), 0, STACK, 3, 456);
    }

    private record Prepared(Account source, Account destination) {}
}
