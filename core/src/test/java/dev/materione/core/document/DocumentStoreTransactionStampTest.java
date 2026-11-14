package dev.materione.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The journal entries of a commit carry the transaction that wrote them, entries from before the field read as
 * before (#344), and a restore of a deleted document is the next revision, never revision 1 again.
 */
class DocumentStoreTransactionStampTest {

    @TempDir
    Path temp;

    @Test
    void aCommitStampsItsEntriesAndOlderLinesStillRead() throws Exception {
        DocumentStore store = new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
        store.createPack("demo", "me");
        store.createDocument("demo", "items/a.yml", "a:\n  material: STICK\n", "me");
        // a line written before the field existed, appended by hand
        Path journal = temp.resolve("revisions/demo/items/a.yml.jsonl");
        String legacy = "{\"rev\":2,\"ts\":1,\"actor\":\"old\",\"action\":\"write\",\"sha\":\"abc\"}\n";
        Files.writeString(journal, Files.readString(journal) + legacy, StandardCharsets.UTF_8);
        assertThat(store.history("demo", "items/a.yml", 10).get(1).transaction()).isNull();
        assertThat(store.history("demo", "items/a.yml", 10).get(1).actor()).isEqualTo("old");

        // the document is not at the legacy hash, so a read journals an external revision (rev 3) first
        int current = store.read("demo", "items/a.yml").revision();
        assertThat(current).isEqualTo(3);
        store.commit("demo", List.of(
                new DocumentStore.PlannedWrite("items/a.yml", DocumentStore.WriteKind.SET, current,
                        "a:\n  material: BONE\n", null),
                new DocumentStore.PlannedWrite("items/b.yml", DocumentStore.WriteKind.CREATE, 0,
                        "b:\n  material: STICK\n", null)),
                "me", "tx-fixed", (index, write) -> { });

        var history = store.history("demo", "items/a.yml", 10);
        assertThat(history.get(history.size() - 1).transaction()).isEqualTo("tx-fixed");
        assertThat(history.get(history.size() - 1).rev()).isEqualTo(4);
        assertThat(history.get(0).transaction()).isNull();
        assertThat(store.history("demo", "items/b.yml", 10).get(0).transaction()).isEqualTo("tx-fixed");
        assertThat(Files.readString(temp.resolve("revisions/demo/items/b.yml.jsonl"))).contains("\"tx\":\"tx-fixed\"");
    }

    @Test
    void aRestoreAfterADeleteIsTheNextRevisionWithTheGivenAction() throws Exception {
        DocumentStore store = new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
        store.createPack("demo", "me");
        store.createDocument("demo", "items/a.yml", "a:\n  material: STICK\n", "me");
        store.delete("demo", "items/a.yml", 1, "me");
        assertThat(temp.resolve("packs/demo/items/a.yml")).doesNotExist();

        store.commit("demo", List.of(new DocumentStore.PlannedWrite("items/a.yml",
                DocumentStore.WriteKind.RESTORE, 0, "a:\n  material: STICK\n", null, "revert")),
                "me", "tx-r", (index, write) -> { });

        var history = store.history("demo", "items/a.yml", 10);
        assertThat(history).extracting(DocumentStore.Revision::rev).containsExactly(1, 2, 3);
        assertThat(history.get(2).action()).isEqualTo("revert");
        assertThat(history.get(2).transaction()).isEqualTo("tx-r");
        assertThat(store.read("demo", "items/a.yml").revision()).isEqualTo(3);
    }
}
