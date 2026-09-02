package dev.materione.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The external-edit feed end to end on the real filesystem: hand edits are
 * debounced into one journaled {@code external} revision + a pushed change,
 * while editor saves through the same store stay silent (web.md §4.2).
 */
class PackWatcherTest {

    @TempDir
    Path temp;

    private PackWatcher watcher;
    private final List<PackWatcher.Change> changes = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (watcher != null) {
            watcher.close();
        }
    }

    private DocumentStore store() throws IOException {
        DocumentStore store = new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
        store.createPack("demo", "test");
        store.write("demo", "items/wand.yml", 0, "wand:\n  material: STICK\n", "editor:test");
        return store;
    }

    private PackWatcher start(DocumentStore store) throws IOException {
        watcher = new PackWatcher(temp.resolve("packs"), store, changes::add);
        // R5: the watcher never spawns threads itself — the host (here: the
        // test) drives runLoop on its own background thread, exactly what the
        // plugin does via the scheduling gateway's async executor
        Thread loop = new Thread(() -> watcher.runLoop(), "pack-watcher-test");
        loop.setDaemon(true);
        loop.start();
        return watcher;
    }

    private List<PackWatcher.Change> awaitChange(int expected, long seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (changes.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return changes;
    }

    @Test
    void handEditsArriveAsOneJournaledChange() throws Exception {
        DocumentStore store = store();
        start(store);

        Files.writeString(temp.resolve("packs").resolve("demo/items/wand.yml"),
                "wand:\n  material: BLAZE_ROD\n", StandardCharsets.UTF_8);

        awaitChange(1, 10);
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.pack()).isEqualTo("demo");
            assertThat(change.path()).isEqualTo("items/wand.yml");
            assertThat(change.deleted()).isFalse();
        });
        // the push rides a journaled external revision — the editor's next
        // save against the old revision fails honestly instead of clobbering
        assertThat(store.read("demo", "items/wand.yml").revision()).isEqualTo(2);
    }

    @Test
    void handDeletionsArriveAsDeleted() throws Exception {
        DocumentStore store = store();
        start(store);

        Files.delete(temp.resolve("packs").resolve("demo/items/wand.yml"));

        awaitChange(1, 10);
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("items/wand.yml");
            assertThat(change.deleted()).isTrue();
        });
    }

    @Test
    void editorSavesStaySilent() throws Exception {
        DocumentStore store = store();
        start(store);
        Thread.sleep(300);

        DocumentStore.Document read = store.read("demo", "items/wand.yml");
        store.write("demo", "items/wand.yml", read.revision(),
                "wand:\n  material: BLAZE_ROD\n", "editor:test");
        // well past the debounce window: the editor's own save must not push
        Thread.sleep(2000);

        assertThat(changes).isEmpty();
    }

    @Test
    void assetAndTempFilesAreIgnored() throws Exception {
        DocumentStore store = store();
        start(store);

        Files.createDirectories(temp.resolve("packs").resolve("demo/assets/textures"));
        Files.writeString(temp.resolve("packs").resolve("demo/assets/textures/gem.png"), "png");
        Files.writeString(temp.resolve("packs")
                .resolve("demo/items/wand.yml.tmp-123"), "partial", StandardCharsets.UTF_8);
        Thread.sleep(2000);

        assertThat(changes).isEmpty();
    }
}
