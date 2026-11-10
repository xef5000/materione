package dev.materione.plugin.escrow.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Test builders: deterministic ids, named stacks, a list-backed inventory. */
final class Fx {

    private Fx() {}

    static Id128 id(long n) {
        return new Id128(0x0102030405060708L, n);
    }

    static StackBytes stack(String name) {
        return StackBytes.of(name.getBytes(StandardCharsets.UTF_8));
    }

    static Entry held(long n, String stack) {
        return Entry.held(id(n), stack(stack), "shop", (int) n, 1_000L + n);
    }

    static Account account(long gen, Entry... entries) {
        return new Account(gen, List.of(entries));
    }

    /** An inventory of {@code capacity} whole stacks; no merging, every stack is its own slot. */
    static final class ListInventory implements Inventory {

        final List<StackBytes> stacks = new ArrayList<>();
        final int capacity;

        ListInventory(int capacity, String... initial) {
            this.capacity = capacity;
            for (String name : initial) {
                stacks.add(stack(name));
            }
        }

        @Override
        public boolean remove(StackBytes stack) {
            return stacks.remove(stack);
        }

        @Override
        public boolean tryAdd(StackBytes stack) {
            if (stacks.size() >= capacity) {
                return false;
            }
            stacks.add(stack);
            return true;
        }
    }
}
