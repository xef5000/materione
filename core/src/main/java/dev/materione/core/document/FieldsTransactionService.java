package dev.materione.core.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The studio's field save with binaries ({@code authoring.setFields} carrying
 * {@code assets}): one revision-guarded batch of lossless field edits and the
 * assets they reference, committed all-or-nothing. A revision conflict, a
 * rejected asset path, an oversized binary or a failed write leaves neither the
 * document nor any binary changed. It reuses the staging and rollback of
 * {@link ModelTransactionService} ({@link AssetStaging}); the only difference is
 * the write policy: assets are limited to the pack's shipped {@code assets/}
 * tree, so authoring metadata (thumbnails) is still written only by the
 * transaction that produces it.
 */
public final class FieldsTransactionService {

    /** The asset store's writable tree, the same pattern {@link AssetStore#safeAssetPath} enforces. */
    private static final Pattern SHIPPED_ASSET = Pattern.compile("assets/[A-Za-z0-9_./ -]+");

    private final DocumentStore documents;
    private final Path packsRoot;
    private final AuditLog audit;

    public FieldsTransactionService(DocumentStore documents, Path packsRoot, AuditLog audit) {
        this.documents = documents;
        this.packsRoot = packsRoot;
        this.audit = audit;
    }

    /**
     * Applies the edits and writes the assets, or neither. Returns the updated document.
     *
     * @throws DocumentConflictException when {@code expectedRevision} no longer matches
     * @throws java.nio.file.NoSuchFileException when the document does not exist
     * @throws IllegalArgumentException for a rejected asset path, size or edit
     */
    public DocumentStore.Document save(
            String actor, String pack, String path, int expectedRevision,
            List<DocumentStore.FieldEdit> edits, List<ModelTransaction.Asset> assets) throws IOException {
        return save(actor, pack, path, expectedRevision, edits, assets, () -> { });
    }

    /** Same as {@link #save}, with the seam fault-injection tests use to prove rollback. */
    DocumentStore.Document save(
            String actor, String pack, String path, int expectedRevision,
            List<DocumentStore.FieldEdit> edits, List<ModelTransaction.Asset> assets,
            Runnable afterAssetPromotion) throws IOException {
        if (edits.isEmpty() && assets.isEmpty()) {
            throw new IllegalArgumentException("nothing to save: no edits and no assets");
        }
        synchronized (AssetStaging.LOCK) {
            Path packRoot = AssetStaging.packRoot(packsRoot, pack);
            // Fail fast on a stale revision before any binary touches disk; the
            // store re-checks under its own lock, and the rollback covers a race.
            DocumentStore.Document current = documents.read(pack, path);
            if (current.revision() != expectedRevision) {
                throw new DocumentConflictException(
                        "document changed elsewhere: expected rev " + expectedRevision
                                + ", current rev " + current.revision(),
                        expectedRevision, current.revision());
            }
            List<AssetStaging.StagedAsset> staged = AssetStaging.stage(
                    packRoot, assets, relative -> SHIPPED_ASSET.matcher(relative).matches());
            Map<Path, AssetStaging.Backup> backups = new LinkedHashMap<>();
            DocumentStore.Checkpoint checkpoint = documents.checkpoint(pack, path);
            try {
                for (AssetStaging.StagedAsset asset : staged) {
                    backups.put(asset.target(), AssetStaging.Backup.capture(asset.target()));
                    Files.createDirectories(asset.target().getParent());
                    Files.write(asset.temp(), asset.bytes());
                }
                for (AssetStaging.StagedAsset asset : staged) {
                    AssetStaging.move(asset.temp(), asset.target());
                }
                afterAssetPromotion.run();
                // A binary-only save (a paint layer) has no field edits: it still takes a
                // revision, guarded like any write, so a stale studio save conflicts.
                DocumentStore.Document updated = edits.isEmpty()
                        ? documents.recordRevision(pack, path, expectedRevision, "assets", actor)
                        : documents.setScalars(pack, path, expectedRevision, edits, actor);
                audit.record(actor, "authoring.setFields",
                        pack + "/" + path + " (" + edits.size() + " fields, "
                                + staged.size() + " assets)");
                return updated;
            } catch (Exception failure) {
                AssetStaging.restore(backups);
                documents.restore(checkpoint);
                throw failure;
            } finally {
                for (AssetStaging.StagedAsset asset : staged) {
                    Files.deleteIfExists(asset.temp());
                }
            }
        }
    }
}
