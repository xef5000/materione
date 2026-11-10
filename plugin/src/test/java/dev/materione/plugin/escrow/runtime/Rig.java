package dev.materione.plugin.escrow.runtime;

import dev.materione.plugin.escrow.model.Account;
import dev.materione.plugin.escrow.model.AccountCodec;
import dev.materione.plugin.escrow.model.AccountRead;
import dev.materione.plugin.escrow.model.Entry;
import dev.materione.plugin.escrow.model.EntryState;
import dev.materione.plugin.escrow.model.Id128;
import dev.materione.plugin.escrow.model.StackBytes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A server-less rig for the tier-1 runtime: a stack that is just {@code (id, amount)}, a world of window slots, a cursor
 * and an inventory as plain collections, and a player whose "persistent data" is the last bytes written. It also holds
 * the invariants every step of the runtime must keep, so each test states only what is specific to it.
 */
final class Rig {

    /** One stack. {@code sword} items stack to 1, everything else to 64. */
    record Item(String id, int amount) {

        Item {
            if (amount <= 0) {
                throw new IllegalArgumentException("an empty stack is null");
            }
        }
    }

    static Item item(String id, int amount) {
        return new Item(id, amount);
    }

    static final class FakeStacks implements Stacks<Item> {

        @Override
        public boolean empty(Item stack) {
            return stack == null || stack.amount() <= 0;
        }

        @Override
        public int amount(Item stack) {
            return empty(stack) ? 0 : stack.amount();
        }

        @Override
        public int maxStack(Item stack) {
            return !empty(stack) && stack.id().startsWith("sword") ? 1 : 64;
        }

        @Override
        public boolean similar(Item a, Item b) {
            return !empty(a) && !empty(b) && a.id().equals(b.id());
        }

        @Override
        public Item withAmount(Item stack, int amount) {
            return amount <= 0 || empty(stack) ? null : new Item(stack.id(), amount);
        }

        @Override
        public StackBytes encode(Item stack) {
            return StackBytes.of((stack.id() + ":" + stack.amount()).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Item decode(StackBytes bytes) {
            String text = new String(bytes.bytes(), StandardCharsets.UTF_8);
            int colon = text.lastIndexOf(':');
            return new Item(text.substring(0, colon), Integer.parseInt(text.substring(colon + 1)));
        }
    }

    /** The live state of one player: window slots, cursor, inventory (36 storage + offhand at 40). */
    static final class World implements Live<Item> {

        final Map<Integer, Item> top = new TreeMap<>();
        Item cursor;
        final Item[] bag = new Item[41];

        @Override
        public Item top(int slot) {
            return top.get(slot);
        }

        @Override
        public void top(int slot, Item stack) {
            if (stack == null) {
                top.remove(slot);
            } else {
                top.put(slot, stack);
            }
        }

        @Override
        public Item cursor() {
            return cursor;
        }

        @Override
        public void cursor(Item stack) {
            cursor = stack;
        }

        @Override
        public Item bag(int index) {
            return bag[index];
        }

        @Override
        public void bag(int index, Item stack) {
            bag[index] = stack;
        }

        /** Fills the first free storage slots with {@code count} full stacks of {@code id}: a full inventory. */
        void fillBag(String id) {
            for (int i = 0; i < Live.STORAGE; i++) {
                if (bag[i] == null) {
                    bag[i] = item(id, 64);
                }
            }
        }

        int bagCount(String id) {
            int total = 0;
            for (Item stack : bag) {
                total += stack != null && stack.id().equals(id) ? stack.amount() : 0;
            }
            return total;
        }

        World copy() {
            World copy = new World();
            copy.top.putAll(top);
            copy.cursor = cursor;
            System.arraycopy(bag, 0, copy.bag, 0, bag.length);
            return copy;
        }
    }

    final FakeStacks stacks = new FakeStacks();
    final World world = new World();
    final List<byte[]> written = new ArrayList<>();
    final PlayerEscrow escrow = new PlayerEscrow(written::add);
    final AtomicLong ids = new AtomicLong(100);
    final Tier1<Item> tier1 = new Tier1<>(stacks, () -> new Id128(7L, ids.incrementAndGet()), () -> 1_000L);
    static final String GUI = "demo:vault";
    static final java.util.function.IntPredicate SLOTS_0_TO_8 = slot -> slot >= 0 && slot <= 8;

    /** A rig whose player has passed the recovery gate with an empty account. */
    static Rig open() {
        Rig rig = new Rig();
        rig.escrow.open(Account.EMPTY);
        return rig;
    }

    /** The account as the last write left it in the player's data. */
    Account persisted() {
        if (written.isEmpty()) {
            return Account.EMPTY;
        }
        AccountRead read = AccountCodec.decode(written.get(written.size() - 1));
        return ((AccountRead.Readable) read).account();
    }

    /** Every item the player owns outside the cursor: inventory, escrowed slots' entries, owed entries. */
    Map<String, Integer> owned() {
        Map<String, Integer> out = new HashMap<>();
        for (Item stack : world.bag) {
            add(out, stack);
        }
        for (Entry entry : escrow.account().entries()) {
            add(out, stacks.decode(entry.stack()));
        }
        return out;
    }

    private static void add(Map<String, Integer> out, Item stack) {
        if (stack != null) {
            out.merge(stack.id(), stack.amount(), Integer::sum);
        }
    }

    /**
     * The invariants of §5.2 after any step: the account written to the player's data is the live account (I1); every
     * HELD entry is exactly what its slot shows and every non-empty required slot has its entry (I3); and the escrowed
     * slots hold nothing else.
     */
    void assertConsistent(java.util.Collection<Integer> escrowedSlots) {
        if (!written.isEmpty()) {
            org.assertj.core.api.Assertions.assertThat(persisted()).as("persisted account is the live one")
                    .isEqualTo(escrow.account());
        }
        for (int slot : escrowedSlots) {
            Entry entry = Tier1.entryAt(escrow.account(), slot);
            Item shown = world.top(slot);
            if (shown == null) {
                org.assertj.core.api.Assertions.assertThat(entry == null || escrow.carrying().contains(entry.id()))
                        .as("slot %d is empty so it has no entry", slot).isTrue();
            } else {
                org.assertj.core.api.Assertions.assertThat(entry).as("slot %d has an entry", slot).isNotNull();
                org.assertj.core.api.Assertions.assertThat(entry.state()).isEqualTo(EntryState.HELD);
                org.assertj.core.api.Assertions.assertThat(stacks.decode(entry.stack())).isEqualTo(shown);
            }
        }
    }
}
