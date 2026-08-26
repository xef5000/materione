package dev.materione.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Revision semantics are the contract here: monotonic per-document
 * revisions, honest external-change detection, atomic writes, and paths
 * that cannot escape their pack.
 */
class DocumentStoreTest {

    @TempDir
    Path temp;

    private DocumentStore store() {
        return new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
    }

    private void seedSamplePack() throws IOException {
        Path pack = temp.resolve("packs").resolve("sample");
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("pack.yml"), "id: sample\nversion: 1.0.0\nschema_version: 1\n");
        Files.writeString(pack.resolve("items").resolve("sword.yml"), """
                # hand-authored comment that must survive round trips
                iron_sword:
                  material: IRON_SWORD
                  display_name: "Iron Sword"
                """);
    }

    @Test
    void writeReadRoundTripIncrementsRevisions() throws IOException {
        DocumentStore store = store();
        store.createPack("demo", "test");
        int rev1 = store.write("demo", "items/wand.yml", 0,
                "wand:\n  material: STICK\n", "tester");
        assertThat(rev1).isEqualTo(1);

        DocumentStore.Document doc = store.read("demo", "items/wand.yml");
        assertThat(doc.revision()).isEqualTo(1);
        assertThat(doc.raw()).isEqualTo("wand:\n  material: STICK\n");

        int rev2 = store.write("demo", "items/wand.yml", doc.revision(),
                "wand:\n  material: BLAZE_ROD\n", "tester");
        assertThat(rev2).isEqualTo(2);
        assertThat(store.read("demo", "items/wand.yml").revision()).isEqualTo(2);
    }

    @Test
    void staleWritesConflictInsteadOfClobbering() throws IOException {
        DocumentStore store = store();
        store.createPack("demo", "test");
        store.write("demo", "items/a.yml", 0, "a:\n  material: STONE\n", "tester");

        // A second writer saved since our snapshot…
        store.write("demo", "items/a.yml", 1, "a:\n  material: DIRT\n", "other");
        // …so our rev-1-based write must be rejected, naming the current rev.
        assertThatThrownBy(() -> store.write("demo", "items/a.yml", 1, "a:\n  material: SAND\n", "tester"))
                .isInstanceOf(DocumentConflictException.class)
                .extracting(e -> ((DocumentConflictException) e).currentRevision())
                .isEqualTo(2);
        // The other writer's bytes stand.
        assertThat(store.read("demo", "items/a.yml").raw()).contains("DIRT");
    }

    @Test
    void externalEditsAreDetectedAndJournaledOnRead() throws IOException {
        DocumentStore store = store();
        store.createPack("demo", "test");
        int rev = store.write("demo", "items/a.yml", 0, "a:\n  material: STONE\n", "tester");

        Files.writeString(packsPath("demo/items/a.yml"),
                "a:\n  material: GOLD_INGOT\n", StandardCharsets.UTF_8);

        // Reading detects hash drift and journals an external revision.
        DocumentStore.Document doc = store.read("demo", "items/a.yml");
        assertThat(doc.revision()).isEqualTo(rev + 1);
        assertThat(store.history("demo", "items/a.yml", 10))
                .last()
                .extracting(DocumentStore.Revision::action)
                .isEqualTo("external");
        // And the editor's old expectation now conflicts.
        assertThatThrownBy(() -> store.write("demo", "items/a.yml", rev, "x: 1\n", "tester"))
                .isInstanceOf(DocumentConflictException.class);
    }

    @Test
    void deleteIsJournaledAndReappearanceIsExternal() throws IOException {
        DocumentStore store = store();
        store.createPack("demo", "test");
        store.write("demo", "items/a.yml", 0, "a:\n  material: STONE\n", "tester");
        store.delete("demo", "items/a.yml", 1, "tester");
        assertThat(packsPath("demo/items/a.yml")).doesNotExist();

        Files.writeString(packsPath("demo/items/a.yml"), "a: {}\n");
        assertThat(store.read("demo", "items/a.yml").revision()).isEqualTo(3);
    }

    @Test
    void syntaxGateRejectsInvalidYamlBeforeTouchingDisk() throws IOException {
        DocumentStore store = store();
        store.createPack("demo", "test");
        assertThatThrownBy(() -> store.write("demo", "items/bad.yml", 0, "a: [unclosed\n", "tester"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(packsPath("demo/items/bad.yml")).doesNotExist();
    }

    @Test
    void pathsCannotEscapeTheirPackOrUseUnknownTypes() {
        DocumentStore store = store();
        assertThatThrownBy(() -> store.read("demo", "../other/pack.yml"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("demo", "secrets/token.yml"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.read("Bad_Pack", "items/a.yml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void browsingListsPacksAndKeyedResources() throws IOException {
        seedSamplePack();
        DocumentStore store = store();
        assertThat(store.listPacks()).containsExactly(new DocumentStore.PackSummary("sample"));

        var refs = store.listResources(null, null, null);
        assertThat(refs).contains(new DocumentStore.ResourceRef(
                "sample", "items", "items/sword.yml", "iron_sword"));
        assertThat(store.listResources(null, "blocks", null)).isEmpty();
        assertThat(store.listResources(null, null, "iron")).hasSize(1);
    }

    @Test
    void journalsStayBoundedButRevisionNumbersNeverReset() throws IOException {
        DocumentStore store = store();
        store.createPack("demo", "test");
        String content = "a:\n  material: STONE\n";
        int last = 0;
        for (int i = 0; i < 260; i++) {
            content = "a:\n  material: STONE\n  count: " + i + "\n";
            last = store.write("demo", "items/a.yml", last, content, "tester");
        }
        assertThat(last).isEqualTo(260);
        assertThat(store.history("demo", "items/a.yml", 500)).hasSizeLessThanOrEqualTo(200);
        assertThat(store.read("demo", "items/a.yml").revision()).isEqualTo(260);
    }

    @Test
    void createdPacksGetTheCanonicalScaffold() throws IOException {
        DocumentStore store = store();
        store.createPack("my_pack", "tester");
        assertThat(packsPath("my_pack/pack.yml")).exists();
        for (String type : DocumentStore.TYPES) {
            assertThat(packsPath("my_pack/" + type)).isDirectory();
        }
        assertThat(store.read("my_pack", "pack.yml").raw())
                .contains("id: my_pack")
                .contains("schema_version: 1");
        assertThatThrownBy(() -> store.createPack("UPPER", "tester"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.createPack("my_pack", "tester"))
                .isInstanceOf(IllegalStateException.class);
    }

    private Path packsPath(String relative) {
        return temp.resolve("packs").resolve(relative);
    }
}
