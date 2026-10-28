package dev.materione.core.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model-transaction core, ported from the editor op's assertions: the
 * save is atomic — a revision conflict or a mid-transaction failure leaves
 * no partial assets, the relationship manifest gains its entry exactly when
 * the whole transaction lands, and the createOwner path scaffolds the owner
 * with display name and model in one revision.
 */
class ModelTransactionServiceTest {

    @TempDir
    Path temp;

    private DocumentStore documents() {
        return new DocumentStore(temp.resolve("packs"), temp.resolve("revisions"));
    }

    private ModelTransactionService service(DocumentStore documents) {
        return new ModelTransactionService(
                documents, temp.resolve("packs"), new AuditLog(temp.resolve("audit.log")));
    }

    private ModelTransaction attachSpec(int revision, String model) {
        return new ModelTransaction(
                "demo", "items/gem.yml", false, "", "", "",
                "demo:gem", List.of("gem", "model"), revision, "",
                List.of(new ModelTransaction.Asset(
                        "assets/models/gem.json", model.getBytes(StandardCharsets.UTF_8))),
                "editor:session-one");
    }

    // ------------------------------------------------------------------

    @Test
    void savesAssetsPatchesOwnerAndWritesTheRelationshipEntry() throws IOException {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument("demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");

        DocumentStore.Document updated =
                service(documents).save(attachSpec(1, "new model"));

        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.raw()).contains("model: \"demo:gem\"");
        assertThat(Files.readString(temp.resolve("packs/demo/assets/models/gem.json")))
                .isEqualTo("new model");
        assertThat(Files.readString(temp.resolve("packs/demo/.materione/model-links.json")))
                .contains("\"items/gem.yml#gem.model\"")
                .contains("\"model\": \"demo:gem\"");
        assertThat(Files.readString(temp.resolve("audit.log")))
                .contains("authoring.saveModel");
    }

    @Test
    void staleOwnerRevisionConflictsAndLeavesExistingAssetsUntouched() throws IOException {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument("demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");
        Path model = temp.resolve("packs/demo/assets/models/gem.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "old model");
        documents.setScalar("demo", "items/gem.yml", 1,
                List.of("gem", "display_name"), "changed", "other");

        assertThatThrownBy(() -> service(documents).save(attachSpec(1, "new model")))
                .isInstanceOf(DocumentConflictException.class);

        assertThat(Files.readString(model)).isEqualTo("old model");
        assertThat(documents.read("demo", "items/gem.yml").raw()).doesNotContain("model:");
        assertThat(Files.exists(temp.resolve("packs/demo/.materione/model-links.json")))
                .isFalse();
    }

    @Test
    void failureAfterPromotionRestoresAssetsAndDoesNotTouchTheOwner() throws IOException {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument("demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");
        Path model = temp.resolve("packs/demo/assets/models/gem.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "old model");

        assertThatThrownBy(() -> service(documents).save(
                attachSpec(1, "new model"), () -> { throw new IllegalStateException("injected"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(model)).isEqualTo("old model");
        assertThat(documents.read("demo", "items/gem.yml").raw()).doesNotContain("model:");
        // no temp siblings survive a rolled-back staging
        try (var files = Files.list(model.getParent())) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void createsTheOwnerDocumentAndAssetsInOneOperation() throws IOException {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");

        DocumentStore.Document updated = service(documents).save(new ModelTransaction(
                "demo", "", true, "new_gem", "", "New Gem",
                "demo:gem", List.of(), -1, "",
                List.of(new ModelTransaction.Asset(
                        "assets/models/gem.json", "new model".getBytes(StandardCharsets.UTF_8))),
                "editor:session-one"));

        assertThat(updated.path()).isEqualTo("items/new_gem.yml");
        assertThat(updated.revision()).isEqualTo(1);
        assertThat(updated.raw())
                .contains("display_name: New Gem")
                .contains("model: \"demo:gem\"");
        assertThat(Files.readString(temp.resolve("packs/demo/assets/models/gem.json")))
                .isEqualTo("new model");
        assertThat(Files.readString(temp.resolve("packs/demo/.materione/model-links.json")))
                .contains("\"items/new_gem.yml#new_gem.model\"");
    }

    @Test
    void authoringMetadataOtherThanTheThumbnailCacheIsRefused() throws IOException {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument("demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");

        assertThatThrownBy(() -> service(documents).save(new ModelTransaction(
                "demo", "items/gem.yml", false, "", "", "",
                "demo:gem", List.of("gem", "model"), 1, "",
                List.of(new ModelTransaction.Asset(
                        ".materione/model-links.json",
                        "{}".getBytes(StandardCharsets.UTF_8))),
                "editor:session-one")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid asset path");

        assertThat(Files.exists(temp.resolve("packs/demo/assets/models/gem.json"))).isFalse();
    }

    @Test
    void thumbnailsStageBesideTheRelationshipManifest() throws IOException {
        DocumentStore documents = documents();
        documents.createPack("demo", "test");
        documents.createDocument("demo", "items/gem.yml", "gem:\n  material: STICK\n", "test");

        service(documents).save(new ModelTransaction(
                "demo", "items/gem.yml", false, "", "", "",
                "demo:gem", List.of("gem", "model"), 1, "",
                List.of(
                        new ModelTransaction.Asset(
                                "assets/models/gem.json",
                                "model".getBytes(StandardCharsets.UTF_8)),
                        new ModelTransaction.Asset(
                                ".materione/thumbnails/gem.png",
                                "PNG".getBytes(StandardCharsets.UTF_8))),
                "editor:session-one"));

        // authoring metadata lands outside assets/, which the compiler ships
        assertThat(Files.readString(temp.resolve("packs/demo/.materione/thumbnails/gem.png")))
                .isEqualTo("PNG");
    }
}
