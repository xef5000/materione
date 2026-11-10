package dev.materione.plugin.escrow.model;

import dev.materione.plugin.gui.input.InputJournal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * The shipped design, as the negative control (gui-input-durability.md §2, W1, §8): the real
 * {@link InputJournal} mirrors the window into the player's persistent data, and a click the server applies after the
 * handler returned is mirrored one tick later ({@code GuiJournal.mirrorNextTick}); a save can land between the click
 * and its mirror (F6). Release gives the stacks back and drops what does not fit (W6); join restores the journal.
 *
 * <p>The property test must catch this design: it is the executable form of "the old design failed K19".
 */
final class OldMirrorWorld implements Design {

    private static final int CAPACITY = EscrowWorld.CAPACITY;
    private static final String HOLDER = "player";

    private final Fx.ListInventory inventory = new Fx.ListInventory(CAPACITY);
    private final List<StackBytes> window = new ArrayList<>();
    private final Snapshot initial;
    private final List<Saved> saves = new ArrayList<>();

    private byte[] journal;
    private StackBytes cursor;
    private boolean mirrorPending;
    private boolean online = true;
    private int tokens;

    OldMirrorWorld(long seed) {
        var rng = new java.util.SplittableRandom(seed ^ 0x5DEECE66DL);
        for (int i = rng.nextInt(1, 4); i > 0; i--) {
            give();
        }
        this.initial = new Snapshot(List.copyOf(inventory.stacks), null);
    }

    @Override
    public Snapshot initial() {
        return initial;
    }

    @Override
    public List<Saved> saves() {
        return saves;
    }

    @Override
    public byte[] ledgerFile() {
        return new byte[0];
    }

    @Override
    public byte[] nextAppend() {
        return null;
    }

    @Override
    public List<StackBytes> liveHoldings() {
        return Design.concat(inventory.stacks, window);
    }

    @Override
    public List<String> liveViolations() {
        return List.of();
    }

    @Override
    public List<Held> escrowed(Snapshot snapshot) {
        List<Held> held = new ArrayList<>();
        decode(snapshot.data()).forEach(stack -> held.add(new Held(null, stack)));
        return held;
    }

    @Override
    public Outcome recover(Snapshot snapshot, byte[] ledgerFile) {
        var inv = new ArrayList<>(snapshot.inventory());
        var store = new Journal(snapshot.data());
        int dropped = 0;
        for (StackBytes stack : new InputJournal<>(store, new Codec()).restore(HOLDER)) {
            if (inv.size() < CAPACITY) {
                inv.add(stack);
            } else {
                dropped++;
            }
        }
        return new Outcome(inv, List.of(), dropped, new Snapshot(List.copyOf(inv), store.data));
    }

    // ---- steps ------------------------------------------------------------------------------------------------

    @Override
    public String step(RandomGenerator rng, int stepNo) {
        List<Supplier<String>> options = new ArrayList<>();
        if (!online) {
            return rejoin();
        }
        if (inventory.stacks.size() < CAPACITY) {
            weight(options, 2, this::give);
        }
        if (!inventory.stacks.isEmpty()) {
            weight(options, 4, () -> moveIn(rng));
        }
        if (!window.isEmpty()) {
            if (inventory.stacks.size() < CAPACITY) {
                weight(options, 3, () -> moveOut(rng));
            }
            if (cursor == null) {
                weight(options, 2, () -> pickUp(rng));
            }
        }
        if (cursor != null) {
            weight(options, 3, this::place);
        }
        if (mirrorPending) {
            weight(options, 3, this::tick);
        }
        weight(options, 3, this::close);
        weight(options, 5, () -> save(stepNo));
        weight(options, 1, () -> quit(stepNo));
        return options.get(rng.nextInt(options.size())).get();
    }

    private static void weight(List<Supplier<String>> options, int times, Supplier<String> option) {
        for (int i = 0; i < times; i++) {
            options.add(option);
        }
    }

    private String give() {
        inventory.tryAdd(Fx.stack("t" + (++tokens)));
        return "give";
    }

    /** A click the server applies itself: the window changes now, the journal one tick later. */
    private String moveIn(RandomGenerator rng) {
        StackBytes stack = inventory.stacks.get(rng.nextInt(inventory.stacks.size()));
        inventory.remove(stack);
        window.add(stack);
        mirrorPending = true;
        return "moveIn";
    }

    private String moveOut(RandomGenerator rng) {
        StackBytes stack = window.remove(rng.nextInt(window.size()));
        inventory.tryAdd(stack);
        mirrorPending = true;
        return "moveOut";
    }

    private String pickUp(RandomGenerator rng) {
        cursor = window.remove(rng.nextInt(window.size()));
        mirrorPending = true;
        return "pickUp";
    }

    private String place() {
        window.add(cursor);
        cursor = null;
        mirrorPending = true;
        return "place";
    }

    private String tick() {
        mirror();
        return "tick";
    }

    private void mirror() {
        new InputJournal<>(new Journal(journal) {
            @Override
            public void write(String holder, byte[] bytes) {
                journal = bytes;
            }

            @Override
            public void clear(String holder) {
                journal = null;
            }
        }, new Codec()).mirror(HOLDER, window);
        mirrorPending = false;
    }

    /** Release: back to the inventory, overflow dropped at the feet, then mirror the empty window, same call. */
    private String close() {
        if (cursor != null) {
            inventory.tryAdd(cursor);
            cursor = null;
        }
        window.forEach(inventory::tryAdd);
        window.clear();
        mirror();
        return "close";
    }

    private String save(int stepNo) {
        saves.add(new Saved(stepNo, new Snapshot(List.copyOf(inventory.stacks), journal == null ? null : journal.clone())));
        return "save";
    }

    private String quit(int stepNo) {
        close();
        save(stepNo);
        online = false;
        return "quit";
    }

    private String rejoin() {
        Snapshot last = saves.isEmpty() ? initial : saves.get(saves.size() - 1).snapshot();
        Outcome outcome = recover(last, new byte[0]);
        inventory.stacks.clear();
        inventory.stacks.addAll(outcome.inventory());
        journal = outcome.next().data();
        cursor = null;
        online = true;
        return "rejoin";
    }

    // ---- scripted steps (the K19 test) -------------------------------------------------------------------------

    StackBytes firstInventoryStack() {
        return inventory.stacks.get(0);
    }

    void scriptedMoveIn() {
        StackBytes stack = inventory.stacks.get(0);
        inventory.remove(stack);
        window.add(stack);
        mirrorPending = true;
    }

    void scriptedMoveOut() {
        inventory.tryAdd(window.remove(0));
        mirrorPending = true;
    }

    void scriptedTick() {
        mirror();
    }

    Snapshot scriptedSave(int stepNo) {
        save(stepNo);
        return saves.get(saves.size() - 1).snapshot();
    }

    // ---- the journal's store and codec ------------------------------------------------------------------------

    private static class Journal implements InputJournal.Store<String> {
        byte[] data;

        Journal(byte[] data) {
            this.data = data;
        }

        @Override
        public Optional<byte[]> read(String holder) {
            return Optional.ofNullable(data);
        }

        @Override
        public void write(String holder, byte[] bytes) {
            data = bytes;
        }

        @Override
        public void clear(String holder) {
            data = null;
        }
    }

    private static final class Codec implements InputJournal.Codec<StackBytes> {
        @Override
        public byte[] encode(List<StackBytes> items) {
            int size = 4;
            for (StackBytes item : items) {
                size += 4 + item.length();
            }
            ByteBuffer out = ByteBuffer.allocate(size).putInt(items.size());
            items.forEach(item -> out.putInt(item.length()).put(item.bytes()));
            return out.array();
        }

        @Override
        public List<StackBytes> decode(byte[] data) {
            return OldMirrorWorld.decode(data);
        }
    }

    private static List<StackBytes> decode(byte[] data) {
        List<StackBytes> items = new ArrayList<>();
        if (data == null) {
            return items;
        }
        ByteBuffer in = ByteBuffer.wrap(data);
        for (int count = in.getInt(); count > 0; count--) {
            byte[] bytes = new byte[in.getInt()];
            in.get(bytes);
            items.add(StackBytes.of(bytes));
        }
        return items;
    }
}
