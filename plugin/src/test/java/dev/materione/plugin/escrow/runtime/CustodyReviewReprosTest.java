package dev.materione.plugin.escrow.runtime;

import static dev.materione.plugin.escrow.runtime.Rig.GUI;
import static dev.materione.plugin.escrow.runtime.Rig.item;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.Ledger;
import dev.materione.plugin.escrow.model.JointTransfer;
import dev.materione.plugin.escrow.model.Step;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustodyReviewReprosTest {

    @Test
    void lifecycleReleaseCannotTreatAKeyedWindowSlotAsPlayerCustody() {
        Rig rig = Rig.open();
        var keyed = new PdcCustodyAccount(bytes -> {});
        keyed.open(new Account(1, List.of(Entry.held(
                new Id128(7, 7), rig.stacks.encode(item("gem", 5)), "vault", 0, 1L))));
        rig.world.top(0, item("gem", 5));

        rig.tier1.release(keyed, rig.world, GUI, Set.of(0));

        assertThat(keyed.account().entries()).hasSize(1);
        assertThat(rig.world.bagCount("gem"))
                .as("the keyed entry must stay parked, not be adopted into the player account")
                .isZero();
    }

    @Test
    void checkpointMustNotOverwriteAnUnrecoverableAnchorAccount() {
        List<byte[]> writes = new java.util.ArrayList<>();
        var anchor = new PdcCustodyAccount(writes::add);
        anchor.unrecoverable("BAD_CRC");

        boolean touched = anchor.touch();

        assertThat(touched).isFalse();
        assertThat(writes).isEmpty();
    }

    @Test
    void checkpointMustNotOpenOrOverwriteAClosedAccount() {
        List<byte[]> writes = new java.util.ArrayList<>();
        var anchor = new PdcCustodyAccount(writes::add);

        assertThat(anchor.touch()).isFalse();
        assertThat(anchor.gate()).isEqualTo(CustodyAccount.Gate.CLOSED);
        assertThat(writes).isEmpty();
    }

    @Test
    void deathParksAKeyedOrStackAccountInsteadOfAdoptingItIntoPlayerDrops() {
        Rig rig = Rig.open();
        var keyed = new PdcCustodyAccount(bytes -> {});
        keyed.open(new Account(1, List.of(Entry.held(
                new Id128(8, 8), rig.stacks.encode(item("gem", 5)), "vault", 0, 1L))));
        rig.world.top(0, item("gem", 5));

        Tier1.Death<Rig.Item> death =
                rig.tier1.death(keyed, rig.world, GUI, Set.of(0), Ledger.EMPTY, false);

        assertThat(death.drops()).isEmpty();
        assertThat(keyed.account().entries()).hasSize(1);
        assertThat(rig.world.bagCount("gem")).isZero();
    }

    @Test
    void playerCloseDoesNotAdoptOrReturnATransferringStackAsAStray() {
        Rig rig = Rig.open();
        Id128 entryId = new Id128(9, 9);
        rig.escrow.open(new Account(1, List.of(Entry.held(
                entryId, rig.stacks.encode(item("gem", 5)), GUI, 0, 1L))));
        Step.Applied prepared = (Step.Applied) JointTransfer.prepareSource(
                rig.escrow.account(), entryId, new Id128(10, 10));
        rig.escrow.commit(prepared.account(), prepared.encoded());
        rig.world.top(0, item("gem", 5));

        Tier1.Done<Rig.Item> closed = (Tier1.Done<Rig.Item>) rig.tier1.release(
                rig.escrow, rig.world, GUI, Set.of(0));

        assertThat(closed.strays()).isEmpty();
        assertThat(rig.world.bagCount("gem")).isZero();
        assertThat(rig.escrow.account().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.state())
                        .isEqualTo(dev.materione.plugin.escrow.model.EntryState.TRANSFERRING));
    }
}
