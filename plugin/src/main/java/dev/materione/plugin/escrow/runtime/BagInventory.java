package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.model.Inventory;
import dev.materione.plugin.escrow.model.StackBytes;

/**
 * The model's {@link Inventory} port over a player's storage slots (gui-input-durability.md I2): adds merge into
 * similar stacks first and then fill empty slots, in index order, as the platform's own add does, and a stack that
 * does not fit whole is refused with no change (the model owes it instead: there is no drop method, G4).
 *
 * @param <T> one stack
 */
public final class BagInventory<T> implements Inventory {

    private final Live<T> live;
    private final Stacks<T> stacks;

    public BagInventory(Live<T> live, Stacks<T> stacks) {
        this.live = live;
        this.stacks = stacks;
    }

    @Override
    public boolean remove(StackBytes stack) {
        for (int index = 0; index < Live.STORAGE; index++) {
            T held = live.bag(index);
            if (!stacks.empty(held) && stacks.encode(held).equals(stack)) {
                live.bag(index, null);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean tryAdd(StackBytes stack) {
        T wanted;
        try {
            wanted = stacks.decode(stack);
        } catch (RuntimeException unreadable) {
            return false; // a stack this server cannot read stays owed: never guess
        }
        if (stacks.empty(wanted)) {
            return true;
        }
        int count = stacks.amount(wanted);
        int max = Math.max(1, stacks.maxStack(wanted));
        int room = 0;
        for (int index = 0; index < Live.STORAGE && room < count; index++) {
            T held = live.bag(index);
            if (stacks.empty(held)) {
                room += max;
            } else if (stacks.similar(held, wanted)) {
                room += Math.max(0, max - stacks.amount(held));
            }
        }
        if (room < count) {
            return false;
        }
        int left = count;
        for (int index = 0; index < Live.STORAGE && left > 0; index++) {
            T held = live.bag(index);
            if (!stacks.empty(held) && stacks.similar(held, wanted)) {
                int add = Math.min(left, Math.max(0, max - stacks.amount(held)));
                if (add > 0) {
                    live.bag(index, stacks.withAmount(held, stacks.amount(held) + add));
                    left -= add;
                }
            }
        }
        for (int index = 0; index < Live.STORAGE && left > 0; index++) {
            if (stacks.empty(live.bag(index))) {
                int add = Math.min(left, max);
                live.bag(index, stacks.withAmount(wanted, add));
                left -= add;
            }
        }
        return true;
    }
}
