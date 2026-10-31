package dev.materione.plugin.gui.input;

import java.util.ArrayList;
import java.util.List;

/**
 * The plans for the three multi-slot moves an input slot takes part in (logic.md §25): a shift-click
 * into the input slots, a shift-click out of one, and a double-click collection. The server would
 * apply these itself, but its own shift-click also fills display slots of the window, so the window's
 * moves are planned here and applied by {@link InputSlotClicks}.
 *
 * <p>Every plan conserves items: the amounts moved out of the sources and into the targets are the same
 * number, and nothing is created. The planners only read; the caller applies a plan or nothing.
 */
public final class Transfers {

    private Transfers() {}

    /**
     * A slot a transfer may read or write.
     *
     * @param slot the slot's index in its inventory
     * @param amount what it holds now; 0 when empty
     * @param similar whether it holds the same item as the transfer's item (an empty slot is never similar)
     */
    public record Cell(int slot, int amount, boolean similar) {}

    /**
     * One change to one slot.
     *
     * @param slot the slot
     * @param count how many items go into it (for a put) or come out of it (for a take)
     */
    public record Move(int slot, int count) {}

    /**
     * A planned transfer.
     *
     * @param moves the changes, in the order they were planned; never a zero count
     * @param left what did not fit (for a put) or what was not found (for a take)
     */
    public record Plan(List<Move> moves, int left) {

        public Plan {
            moves = List.copyOf(moves);
        }

        /** How many items the plan moves. */
        public int moved() {
            return moves.stream().mapToInt(Move::count).sum();
        }
    }

    /**
     * Puts {@code count} items into {@code cells}, the way a shift-click does: first into the similar
     * stacks that have room, in order, then into the empty cells, in order.
     *
     * @param count how many to put
     * @param maxStack the largest stack of the item
     * @param cells the candidate slots, in the order to try them; a cell is only used when it is empty or similar
     */
    public static Plan put(int count, int maxStack, List<Cell> cells) {
        int left = Math.max(0, count);
        var moves = new ArrayList<Move>();
        for (Cell cell : cells) {
            if (left == 0) {
                break;
            }
            if (cell.similar() && cell.amount() > 0 && cell.amount() < maxStack) {
                left = addTo(moves, cell.slot(), Math.min(left, maxStack - cell.amount()), left);
            }
        }
        for (Cell cell : cells) {
            if (left == 0) {
                break;
            }
            if (cell.amount() == 0) {
                left = addTo(moves, cell.slot(), Math.min(left, maxStack), left);
            }
        }
        return new Plan(moves, left);
    }

    /**
     * Takes up to {@code room} items from the similar {@code cells}, in order (a double-click collection
     * fills the cursor from the matching slots).
     *
     * @param room how many the cursor can still take
     * @param cells the candidate slots, in the order to take from
     */
    public static Plan take(int room, List<Cell> cells) {
        int left = Math.max(0, room);
        var moves = new ArrayList<Move>();
        for (Cell cell : cells) {
            if (left == 0) {
                break;
            }
            if (cell.similar() && cell.amount() > 0) {
                int taken = Math.min(left, cell.amount());
                moves.add(new Move(cell.slot(), taken));
                left -= taken;
            }
        }
        return new Plan(moves, left);
    }

    /** Records a move of {@code count} (when positive) and returns what is still to place. */
    private static int addTo(List<Move> moves, int slot, int count, int left) {
        if (count <= 0) {
            return left;
        }
        moves.add(new Move(slot, count));
        return left - count;
    }
}
