package dev.materione.plugin.escrow.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The tier-1 steps (gui-input-durability.md §5.4, §5.5), one assertion set per row of the action table. */
class EscrowTransferTest {

    private static Step.Applied applied(Step step) {
        assertThat(step).isInstanceOf(Step.Applied.class);
        return (Step.Applied) step;
    }

    @Test
    void moveInTakesTheStackFromTheInventoryAndEncodesTheNewAccountInTheSameStep() {
        var inventory = new Fx.ListInventory(4, "sword", "apple");
        Step.Applied step = applied(EscrowTransfer.moveIn(Account.EMPTY, inventory, Fx.held(1, "sword")));

        assertThat(inventory.stacks).containsExactly(Fx.stack("apple"));
        assertThat(step.account().gen()).isEqualTo(1);
        assertThat(step.account().entries()).containsExactly(Fx.held(1, "sword"));
        assertThat(step.encoded()).isEqualTo(AccountCodec.encode(step.account()));
    }

    @Test
    void moveInOfAStackTheInventoryDoesNotHoldChangesNothing() {
        var inventory = new Fx.ListInventory(4, "apple");
        assertThat(EscrowTransfer.moveIn(Account.EMPTY, inventory, Fx.held(1, "sword")))
                .isEqualTo(new Step.Refused(Refusal.NO_SUCH_STACK));
        assertThat(inventory.stacks).containsExactly(Fx.stack("apple"));
    }

    @Test
    void moveOutReturnsToTheInventoryInTheSameStep() {
        var inventory = new Fx.ListInventory(4);
        Account account = Fx.account(5, Fx.held(1, "sword"), Fx.held(2, "apple"));
        Step.Applied step = applied(EscrowTransfer.moveOut(account, inventory, Fx.id(1)));

        assertThat(inventory.stacks).containsExactly(Fx.stack("sword"));
        assertThat(step.account().entries()).containsExactly(Fx.held(2, "apple"));
        assertThat(step.account().gen()).isEqualTo(6);
        assertThat(step.returned()).containsExactly(Fx.stack("sword"));
    }

    @Test
    void moveOutIntoAFullInventoryMakesTheEntryOwedAndDropsNothing() {
        var inventory = new Fx.ListInventory(1, "dirt");
        Account account = Fx.account(5, Fx.held(1, "sword"));
        Step.Applied step = applied(EscrowTransfer.moveOut(account, inventory, Fx.id(1)));

        assertThat(inventory.stacks).containsExactly(Fx.stack("dirt"));
        assertThat(step.account().entries()).containsExactly(Fx.held(1, "sword").owed());
        assertThat(step.owed()).containsExactly(Fx.stack("sword"));

        // an owed entry that still does not fit is a refusal, not a state change
        assertThat(EscrowTransfer.moveOut(step.account(), inventory, Fx.id(1)))
                .isEqualTo(new Step.Refused(Refusal.INVENTORY_FULL));
    }

    @Test
    void aFrozenEntryIsBusyForEveryPlayerAction() {
        Account account = Fx.account(5, Fx.held(1, "sword").exiting(Fx.id(7)));
        var inventory = new Fx.ListInventory(4);
        assertThat(EscrowTransfer.moveOut(account, inventory, Fx.id(1))).isEqualTo(new Step.Refused(Refusal.BUSY));
        assertThat(EscrowTransfer.pickUp(account, Fx.id(1))).isEqualTo(new Step.Refused(Refusal.BUSY));
        assertThat(EscrowTransfer.editStack(account, Fx.id(1), Fx.stack("x"))).isEqualTo(new Step.Refused(Refusal.BUSY));
        assertThat(EscrowTransfer.swap(account, Fx.id(1), Fx.held(2, "y"))).isEqualTo(new Step.Refused(Refusal.BUSY));
        assertThat(inventory.stacks).isEmpty();
    }

    @Test
    void pickUpHandsTheStackToTheCursorAndTheAccountForgetsItInTheSameStep() {
        Account account = Fx.account(5, Fx.held(1, "sword"));
        Step.Applied step = applied(EscrowTransfer.pickUp(account, Fx.id(1)));
        assertThat(step.released()).containsExactly(Fx.stack("sword"));
        assertThat(step.account().entries()).isEmpty();
    }

    @Test
    void placeAndSwapKeepTheOtherEntriesAndSpendOneGeneration() {
        Account account = Fx.account(5, Fx.held(1, "sword"), Fx.held(2, "apple"));
        Step.Applied swapped = applied(EscrowTransfer.swap(account, Fx.id(1), Fx.held(3, "shield")));
        assertThat(swapped.released()).containsExactly(Fx.stack("sword"));
        assertThat(swapped.account().entries()).containsExactly(Fx.held(3, "shield"), Fx.held(2, "apple"));
        assertThat(swapped.account().gen()).isEqualTo(6);

        Step.Applied placed = applied(EscrowTransfer.place(account, Fx.held(4, "bow")));
        assertThat(placed.account().entries()).hasSize(3);
    }

    @Test
    void closeReturnsOwedFirstThenHeldAndOwesWhatDoesNotFitAndLeavesFrozenAlone() {
        Account account = Fx.account(
                5,
                Fx.held(1, "held-a"),
                Fx.held(2, "owed-b").owed(),
                Fx.held(3, "frozen-c").exiting(Fx.id(9)),
                Fx.held(4, "held-d"));
        var inventory = new Fx.ListInventory(2);
        Step.Applied step = applied(EscrowTransfer.close(account, inventory));

        assertThat(inventory.stacks).containsExactly(Fx.stack("owed-b"), Fx.stack("held-a"));
        assertThat(step.account().entries())
                .containsExactly(Fx.held(3, "frozen-c").exiting(Fx.id(9)), Fx.held(4, "held-d").owed());
        assertThat(step.owed()).containsExactly(Fx.stack("held-d"));
    }

    @Test
    void closeWithNothingToReleaseIsANoOpThatSpendsNoGeneration() {
        Account account = Fx.account(5, Fx.held(3, "frozen").exiting(Fx.id(9)));
        assertThat(EscrowTransfer.close(account, new Fx.ListInventory(4))).isEqualTo(new Step.NoOp(account));
        Account owedOnly = Fx.account(5, Fx.held(1, "a").owed());
        assertThat(EscrowTransfer.claim(owedOnly, new Fx.ListInventory(0))).isEqualTo(new Step.NoOp(owedOnly));
    }

    @Test
    void claimLeavesHeldEntriesInTheirSlots() {
        Account account = Fx.account(5, Fx.held(1, "held"), Fx.held(2, "owed").owed());
        var inventory = new Fx.ListInventory(4);
        Step.Applied step = applied(EscrowTransfer.claim(account, inventory));
        assertThat(inventory.stacks).containsExactly(Fx.stack("owed"));
        assertThat(step.account().entries()).containsExactly(Fx.held(1, "held"));
    }

    @Test
    void deathWithKeepInventoryReturnsEntriesAndWithoutItReleasesThemForTheDropList() {
        Account account = Fx.account(
                5, Fx.held(1, "held"), Fx.held(2, "owed").owed(), Fx.held(3, "frozen").exiting(Fx.id(9)));

        var kept = new Fx.ListInventory(4);
        Step.Applied keep = applied(EscrowTransfer.death(account, Ledger.EMPTY, kept, true));
        assertThat(kept.stacks).containsExactly(Fx.stack("held"), Fx.stack("frozen"));
        assertThat(keep.account().entries()).containsExactly(Fx.held(2, "owed").owed());

        var lost = new Fx.ListInventory(4);
        Step.Applied drop = applied(EscrowTransfer.death(account, Ledger.EMPTY, lost, false));
        assertThat(lost.stacks).isEmpty();
        assertThat(drop.released()).containsExactly(Fx.stack("held"), Fx.stack("frozen"));
        assertThat(drop.account().entries()).containsExactly(Fx.held(2, "owed").owed());
    }

    @Test
    void deathFollowsTheLedgerForAFrozenEntry() {
        Entry frozen = Fx.held(3, "frozen").exiting(Fx.id(9));
        Account account = Fx.account(5, frozen);
        Ledger decided = Ledger.EMPTY.appended(Exits.intent(frozen, ExitKind.CONSUME, 1, 0));
        var inventory = new Fx.ListInventory(4);
        Step.Applied step = applied(EscrowTransfer.death(account, decided, inventory, true));
        assertThat(inventory.stacks).isEmpty();
        assertThat(step.consumed()).containsExactly(Fx.stack("frozen"));
        assertThat(step.account().entries()).isEmpty();
    }

    @Test
    void reslotAndEditKeepTheEntryIdAndSpendOneGeneration() {
        Account account = Fx.account(5, Fx.held(1, "sword"));
        Step.Applied slotted = applied(EscrowTransfer.reslot(account, Fx.id(1), 22));
        assertThat(slotted.account().entries().get(0).slotHint()).isEqualTo(22);
        Step.Applied edited = applied(EscrowTransfer.editStack(account, Fx.id(1), Fx.stack("sword+1")));
        assertThat(edited.account().entries().get(0).stack()).isEqualTo(Fx.stack("sword+1"));
        assertThat(EscrowTransfer.reslot(account, Fx.id(1), -1)).isEqualTo(new Step.Refused(Refusal.INVALID));
        assertThat(EscrowTransfer.reslot(account, Fx.id(404), 1)).isEqualTo(new Step.Refused(Refusal.STALE));
    }

    @Test
    void legacyStacksAreAdoptedAsOwedInOneGeneration() {
        List<Entry> legacy = List.of(Fx.held(7, "a").owed(), Fx.held(8, "b").owed());
        Step.Applied step = applied(EscrowTransfer.adoptLegacy(Account.EMPTY, legacy));
        assertThat(step.account().gen()).isEqualTo(1);
        assertThat(step.account().entries()).isEqualTo(legacy);
        assertThat(EscrowTransfer.adoptLegacy(Account.EMPTY, List.of(Fx.held(9, "held")))).isEqualTo(
                new Step.Refused(Refusal.INVALID));
    }
}
