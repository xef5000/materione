package dev.materione.plugin.gui.input;

import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * The crash journal of the items a player holds in an open window's input slots (logic.md §25, decision 12).
 * While the window is open those items are not in the player's saved inventory, so a hard crash after an
 * autosave would lose them. The journal copies them into the player's own persistent data: after every change
 * to the window, the journal holds exactly the items the window holds, and a join gives back what the journal
 * still holds.
 *
 * <p>The invariant that makes it duplication-proof: an item is in the inventory or in the journal, never both.
 * A normal release gives the items to the inventory and then mirrors the (now empty) window into the journal,
 * in the same tick, so a save can never see both. {@link #restore} clears the journal before it hands items
 * out, so a second restore hands out nothing.
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
     * Takes what the journal holds, to give back to the holder once (a join after a crash). The journal is
     * empty afterwards, so the items are handed out at most once.
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
