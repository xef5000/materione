package dev.materione.plugin.gui.input;

import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * The recovery journal of the items a player holds in an open window's input slots (logic.md §25, decision 12).
 * While the window is open those items are not in the player's saved inventory. The journal copies them into the
 * player's own persistent data: after a change to the window, the journal holds the items the window holds, and a
 * join gives back what the journal still holds.
 *
 * <p><b>Best-effort, not crash-safe.</b> An item should be in the inventory or in the journal, never both. A
 * synchronous step keeps that: a release on the owner thread gives the items to the inventory and then mirrors the
 * (now empty) window in the same call. The guarantee does not hold across an autosave. A single-slot click or drag
 * is applied by the server after the handler returns, so its mirror runs one tick later, and an autosave can run in
 * between. A save then captures either a move-in (the item is gone from the inventory and not yet journaled, so a
 * crash loses it) or a move-out (the item is back in the inventory and the journal still holds it, so a crash
 * duplicates it). See docs/architecture/gui-input-durability.md §2 (W1, W2) for the analysis and the design that
 * replaces this journal.
 *
 * <p>{@link #restore} clears the journal before it hands items out, so a second restore in the same live data hands
 * out nothing. A crash after the hand-out and before the next save can still bring the journal back, and items that
 * did not fit in the inventory may then be duplicated (§2, W6).
 *
 * <p>Generic over the holder ({@code K}: the player) and the stack ({@code T}) so the rules are unit-tested
 * without a server; the Bukkit store and codec are in {@link GuiJournal}.
 *
 * @param <K> the holder whose data the journal lives in
 * @param <T> one stack
 */
public final class InputJournal<K, T> {

    /** Where the journal bytes live (the player's persistent data, in production). */
    public interface Store<K> {

        /** The bytes last written for this holder, if any. */
        Optional<byte[]> read(K holder);

        void write(K holder, byte[] data);

        void clear(K holder);
    }

    /** Turns a list of stacks into bytes and back. */
    public interface Codec<T> {

        byte[] encode(List<T> items);

        List<T> decode(byte[] data);
    }

    private final Store<K> store;
    private final Codec<T> codec;

    public InputJournal(Store<K> store, Codec<T> codec) {
        this.store = Objects.requireNonNull(store, "store");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Makes the journal hold exactly {@code held}: nothing clears it, anything else replaces what it held.
     * Call it after every change to the window's input slots, with what the window holds now.
     */
    public void mirror(K holder, List<T> held) {
        if (held.isEmpty()) {
            store.clear(holder);
        } else {
            store.write(holder, codec.encode(List.copyOf(held)));
        }
    }

    /**
     * Takes what the journal holds, to give back to the holder (a join after a crash). The journal is empty
     * afterwards in the live data, so this hands the items out at most once per live session; a crash before the
     * next save can hand them out again (§2, W6).
     */
    public List<T> restore(K holder) {
        Optional<byte[]> data = store.read(holder);
        if (data.isEmpty()) {
            return List.of();
        }
        store.clear(holder);
        return List.copyOf(codec.decode(data.get()));
    }
}
