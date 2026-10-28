package dev.materione.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The field save with binaries (authoring.setFields with assets): the edits and
 * the assets land together or not at all, on the same rules as the model
 * save-back, and only assets/ may be written.
 */
class FieldsTransactionServiceTest {

    private static final String GUI = "guis/shop.yml";
    private static final String GUI_RAW = "shop:\n  title: Shop\n  rows: 3\n  layers: []\n";

    @TempDir
    Path temp;

    private DocumentStore documents() {
        return new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
    }

    private FieldsTransactionService service(DocumentStore documents) {
        return new FieldsTransactionService(
                documents, temp.resolve("packs"), new AuditLog(temp.resolve("audit.log")));
    }

    /** A pack with one GUI at revision 1. */
    private DocumentStore seeded() throws IOException {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument("demo", GUI, GUI_RAW, "test");
        return documents;
    }

    private static List<DocumentStore.FieldEdit> titleEdit(String title) {
        return List.of(new DocumentStore.FieldEdit(List.of("shop", "title"), title));
    }

    private static ModelTransaction.Asset art(String path, String text) {
        return new ModelTransaction.Asset(path, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void editsAndAssetsLandTogetherInOneRevision() throws IOException {
        DocumentStore documents = seeded();

        DocumentStore.Document updated = service(documents).save(
                "editor:session-one", "demo", GUI, 1, titleEdit("Market"),
                List.of(art("assets/textures/gui/shop/art.png", "png-bytes")));

        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.raw()).contains("title: Market");
        assertThat(Files.readString(temp.resolve("packs/demo/assets/textures/gui/shop/art.png")))
                .isEqualTo("png-bytes");
        assertThat(Files.readString(temp.resolve("audit.log")))
                .contains("authoring.setFields").contains("1 fields, 1 assets");
        assertThat(Files.list(temp.resolve("packs/demo/assets/textures/gui/shop")).toList())
                .extracting(p -> p.getFileName().toString())
                .containsExactly("art.png");
    }

    @Test
    void aStaleRevisionWritesNeitherTheAssetNorTheEdit() throws IOException {
        DocumentStore documents = seeded();

        assertThatThrownBy(() -> service(documents).save(
                "editor:session-one", "demo", GUI, 0, titleEdit("Market"),
                List.of(art("assets/textures/gui/shop/art.png", "png"))))
                .isInstanceOf(DocumentConflictException.class);

        assertThat(Files.exists(temp.resolve("packs/demo/assets/textures/gui/shop/art.png"))).isFalse();
        assertThat(documents.read("demo", GUI).raw()).contains("title: Shop");
        assertThat(documents.read("demo", GUI).revision()).isEqualTo(1);
    }

    @Test
    void aMissingDocumentIsReportedAndNothingIsWritten() throws IOException {
        DocumentStore documents = seeded();

        assertThatThrownBy(() -> service(documents).save(
                "editor:session-one", "demo", "guis/missing.yml", 1, titleEdit("X"),
                List.of(art("assets/textures/gui/x.png", "png"))))
                .isInstanceOf(NoSuchFileException.class);
        assertThat(Files.exists(temp.resolve("packs/demo/assets/textures/gui/x.png"))).isFalse();
    }

    @Test
    void onlyTheShippedAssetTreeIsWritable() throws IOException {
        DocumentStore documents = seeded();
        FieldsTransactionService service = service(documents);

        for (String path : List.of(
                ".materione/thumbnails/shop.png",
                "assets/../pack.yml",
                "guis/shop.yml",
                "assets/textures/../../pack.yml")) {
            assertThatThrownBy(() -> service.save("editor:session-one", "demo", GUI, 1, titleEdit("X"),
                    List.of(art(path, "bytes"))))
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid asset path");
        }
        assertThat(documents.read("demo", GUI).revision()).isEqualTo(1);
    }

    @Test
    void aBadEditAfterAssetsAreStagedRestoresEveryFile() throws IOException {
        DocumentStore documents = seeded();
        Path existing = temp.resolve("packs/demo/assets/textures/gui/shop/art.png");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "old");

        // no edits is rejected by the document store, after the assets were already promoted
        assertThatThrownBy(() -> service(documents).save(
                "editor:session-one", "demo", GUI, 1, List.of(),
                List.of(art("assets/textures/gui/shop/art.png", "new"),
                        art("assets/textures/gui/shop/extra.png", "new"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no edits");

        assertThat(Files.readString(existing)).isEqualTo("old");
        assertThat(Files.exists(temp.resolve("packs/demo/assets/textures/gui/shop/extra.png"))).isFalse();
        assertThat(documents.read("demo", GUI).raw()).contains("title: Shop");
        assertThat(documents.read("demo", GUI).revision()).isEqualTo(1);
    }

    @Test
    void aFailureAfterPromotionRollsBackAssetsAndTheDocument() throws IOException {
        DocumentStore documents = seeded();
        Path created = temp.resolve("packs/demo/assets/textures/gui/shop/new.png");

        assertThatThrownBy(() -> service(documents).save(
                "editor:session-one", "demo", GUI, 1, titleEdit("Market"),
                List.of(art("assets/textures/gui/shop/new.png", "new")),
                () -> {
                    throw new IllegalStateException("injected failure after promotion");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.exists(created)).isFalse();
        assertThat(documents.read("demo", GUI).raw()).contains("title: Shop");
        assertThat(documents.read("demo", GUI).revision()).isEqualTo(1);
    }

    @Test
    void sizeAndDuplicateLimitsAreEnforcedBeforeAnyWrite() throws IOException {
        DocumentStore documents = seeded();
        FieldsTransactionService service = service(documents);

        assertThatThrownBy(() -> service.save("editor:session-one", "demo", GUI, 1, titleEdit("X"),
                List.of(art("assets/textures/gui/a.png", "x"), art("assets/textures/gui/a.png", "y"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate asset path");
        assertThatThrownBy(() -> service.save("editor:session-one", "demo", GUI, 1, titleEdit("X"),
                List.of(new ModelTransaction.Asset("assets/textures/gui/big.png",
                        new byte[AssetStaging.MAX_BYTES + 1]))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 8 MiB");
        assertThat(Files.exists(temp.resolve("packs/demo/assets/textures/gui/a.png"))).isFalse();
        assertThat(documents.read("demo", GUI).revision()).isEqualTo(1);
    }
}
