package dev.materione.plugin.gui.input;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.input.Transfers.Cell;
import dev.materione.plugin.gui.input.Transfers.Move;
import dev.materione.plugin.gui.input.Transfers.Plan;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The planned multi-slot moves (logic.md §25). Each plan conserves items: what leaves the sources is what
 * arrives in the targets, and nothing is created or lost, whatever the stacks look like.
 */
class TransfersTest {

    @Test
    void aShiftIntoTheInputsMergesIntoSimilarStacksFirst() {
        var cells = List.of(
                new Cell(0, 0, false),   // empty
                new Cell(1, 60, true),   // similar, room for 4
                new Cell(2, 0, false));  // empty
        Plan plan = Transfers.put(10, 64, cells);

        assertThat(plan.moves()).containsExactly(new Move(1, 4), new Move(0, 6));
        assertThat(plan.left()).isZero();
    }

    @Test
    void aShiftIntoFullInputsLeavesTheRemainderWithTheSource() {
        var cells = List.of(new Cell(0, 64, true), new Cell(1, 0, false));
        Plan plan = Transfers.put(70, 64, cells);

        assertThat(plan.moves()).containsExactly(new Move(1, 64));
        assertThat(plan.left()).as("six did not fit and stay in the player's hand").isEqualTo(6);
    }

    @Test
    void aDissimilarStackIsNeverMergedInto() {
        var cells = List.of(new Cell(0, 5, false));
        Plan plan = Transfers.put(3, 64, cells);

        assertThat(plan.moves()).isEmpty();
        assertThat(plan.left()).isEqualTo(3);
    }

    @Test
    void aTakeOnlyReadsSimilarStacksUpToTheRoomLeft() {
        var cells = List.of(
                new Cell(0, 8, true),
                new Cell(1, 9, false),   // a different item: never taken
                new Cell(2, 12, true));
        Plan plan = Transfers.take(16, cells);

        assertThat(plan.moves()).containsExactly(new Move(0, 8), new Move(2, 8));
        assertThat(plan.left()).isZero();
        assertThat(plan.moves()).extracting(Move::slot).doesNotContain(1);
    }

    @Test
    void everyPlanConservesItemsForEveryShape() {
        // a grid of cases: the planned moves always account for exactly the amount asked for
        for (int count = 0; count <= 130; count += 7) {
            for (int first = 0; first <= 64; first += 9) {
                var cells = List.of(
                        new Cell(0, first, first > 0),
                        new Cell(1, 0, false),
                        new Cell(2, Math.min(64, first + 3), first > 0),
                        new Cell(3, 0, false));
                Plan put = Transfers.put(count, 64, cells);
                assertThat(put.moved() + put.left()).as("put count " + count + " first " + first).isEqualTo(count);
                for (Move move : put.moves()) {
                    assertThat(move.count()).isPositive();
                }

                Plan take = Transfers.take(count, cells);
                assertThat(take.moved() + take.left()).as("take count " + count + " first " + first).isEqualTo(count);
            }
        }
    }

    @Test
    void aTakeNeverRemovesMoreFromAStackThanItHolds() {
        var cells = List.of(new Cell(0, 3, true));
        Plan plan = Transfers.take(10, cells);

        assertThat(plan.moves()).containsExactly(new Move(0, 3));
        assertThat(plan.left()).isEqualTo(7);
    }
}
