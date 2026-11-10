package dev.materione.plugin.escrow.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * One design of "who owns the items in a window's input slots", driven by the crash-boundary property test
 * (gui-input-durability.md §8). A design is a live player (inventory, cursor, window) plus whatever it keeps for a
 * crash; the test runs random steps against it, takes the saves the server would take, and asks what a boot would
 * recover from any save and the ledger as it stood.
 */
interface Design {

    /** What a completed player save holds: the inventory and the plugin's persistent value. */
    record Snapshot(List<StackBytes> inventory, byte[] data) {}

    /** A completed save and the step after which it completed. */
    record Saved(int step, Snapshot snapshot) {}

    /** One stack a snapshot escrows. {@code entryId} is null for a design that has no entry ids. */
    record Held(Id128 entryId, StackBytes stack) {}

    /**
     * What a boot recovered.
     *
     * @param inventory   the inventory after recovery
     * @param owed        stacks still owed by an account
     * @param worldWrites stacks the design put in a world (a drop at the feet): G4 says never
     * @param next        the snapshot a save right after recovery would write (the second-crash check, K16)
     */
    record Outcome(List<StackBytes> inventory, List<StackBytes> owed, int worldWrites, Snapshot next) {}

    /** Performs one randomly chosen applicable step and describes it. */
    String step(RandomGenerator rng, int stepNo);

    /** The disk before any step: the state the player joined with. */
    Snapshot initial();

    /** Every completed save so far, oldest first. */
    List<Saved> saves();

    /** The ledger file as the disk holds it now. */
    byte[] ledgerFile();

    /** The bytes the next ledger append would write, or null when none is queued. */
    byte[] nextAppend();

    /** What the player owns now: inventory and escrowed stacks, not the cursor. */
    List<StackBytes> liveHoldings();

    /** Violations of the design's own live invariants noticed while stepping (empty when it has none to check). */
    List<String> liveViolations();

    /** The stacks escrowed in {@code snapshot}. */
    List<Held> escrowed(Snapshot snapshot);

    /** What a boot recovers from {@code snapshot} and a ledger file; independent of the live state. */
    Outcome recover(Snapshot snapshot, byte[] ledgerFile);

    // ---- shared helpers ---------------------------------------------------------------------------------------

    /** A stack's name as the tests write it. */
    static String name(StackBytes stack) {
        return new String(stack.bytes(), StandardCharsets.UTF_8);
    }

    /** The multiset of names, as a sorted map name to count. */
    static Map<String, Integer> multiset(List<StackBytes> stacks) {
        Map<String, Integer> counts = new TreeMap<>();
        for (StackBytes stack : stacks) {
            counts.merge(name(stack), 1, Integer::sum);
        }
        return counts;
    }

    static List<StackBytes> concat(List<StackBytes> first, List<StackBytes> second) {
        List<StackBytes> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
