package dev.materione.plugin.gui.input;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The crash journal's rules (logic.md §25, decision 12), with the player's saved data, the inventory and the
 * window modelled as plain lists. "Saved" is what survives a crash: the persistent data and the inventory as
 * the last autosave wrote them.
 */
class InputJournalTest {

    private static final String PLAYER = "probe";

    /** The player's persistent data: the one thing that survives a crash besides the inventory. */
    private final Map<String, byte[]> persistentData = new HashMap<>();

    private final InputJournal.Store<String> store = new InputJournal.Store<>() {
        @Override
        public Optional<byte[]> read(String holder) {
            return Optional.ofNullable(persistentData.get(holder));
        }

        @Override
        public void write(String holder, byte[] data) {
            persistentData.put(holder, data);
        }

        @Override
        public void clear(String holder) {
            persistentData.remove(holder);
        }
    };

    private final InputJournal.Codec<String> codec = new InputJournal.Codec<>() {
        @Override
        public byte[] encode(List<String> items) {
            return String.join("\n", items).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<String> decode(byte[] data) {
            String text = new String(data, StandardCharsets.UTF_8);
            return text.isEmpty() ? List.of() : List.of(text.split("\n"));
        }
    };

    /** A fresh journal over the same persistent data, as after a restart. */
    private InputJournal<String, String> journal() {
        return new InputJournal<>(store, codec);
    }

    @Test
    void aNormalCloseNeverReturnsItemsTwice() {
        var inventory = new ArrayList<String>();
        var window = new ArrayList<>(List.of("ruby", "emerald"));
        journal().mirror(PLAYER, window);

        // the close: the items go to the inventory, the window is emptied, and the journal follows in the same tick
        inventory.addAll(window);
        window.clear();
        journal().mirror(PLAYER, window);

        // a restart after the close: the journal has nothing to give back, so the items are not handed out again
        assertThat(journal().restore(PLAYER)).isEmpty();
        assertThat(inventory).containsExactly("ruby", "emerald");
    }

    @Test
    void aCrashBeforeTheCloseRestoresTheItemsOnceOnJoin() {
        var savedInventory = new ArrayList<String>(List.of("sword")); // without the ruby, which is in the window
        var window = new ArrayList<>(List.of("ruby"));
        journal().mirror(PLAYER, window);

        // the crash: the window and the server are gone; the saved inventory and the persistent data remain
        var afterJoin = new ArrayList<>(savedInventory);
        for (String item : journal().restore(PLAYER)) {
            afterJoin.add(item);
        }

        assertThat(afterJoin).containsExactly("sword", "ruby");
    }

    @Test
    void aSecondRestoreHandsOutNothing() {
        journal().mirror(PLAYER, List.of("ruby"));

        assertThat(journal().restore(PLAYER)).containsExactly("ruby");
        assertThat(journal().restore(PLAYER)).as("the journal is empty once it has been restored").isEmpty();
        assertThat(persistentData).isEmpty();
    }

    @Test
    void aMirrorReplacesWhatTheJournalHeldAndAnEmptyWindowClearsIt() {
        journal().mirror(PLAYER, List.of("ruby"));
        journal().mirror(PLAYER, List.of("ruby", "emerald"));
        assertThat(journal().restore(PLAYER)).containsExactly("ruby", "emerald");

        journal().mirror(PLAYER, List.of("ruby"));
        journal().mirror(PLAYER, List.of());
        assertThat(persistentData).as("nothing held: nothing journaled").isEmpty();
    }

    @Test
    void aReleaseWithoutAMirrorWouldReturnTheItemTwice() {
        // the invariant the close path keeps: the journal must follow every release. Without the mirror, the
        // journal still holds the ruby after the inventory has it, and a restart would give it out again.
        var inventory = new ArrayList<String>();
        var window = new ArrayList<>(List.of("ruby"));
        journal().mirror(PLAYER, window);
        inventory.addAll(window);
        window.clear();

        assertThat(journal().restore(PLAYER)).as("the stale journal entry the mirror prevents").containsExactly("ruby");
        assertThat(inventory).containsExactly("ruby");
    }
}
