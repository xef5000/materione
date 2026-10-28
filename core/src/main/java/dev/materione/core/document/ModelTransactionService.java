package dev.materione.core.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The atomic Blockbench save-back (master §5.6, web.md §4.3), extracted from
 * the editor RPC surface: stages model JSON, textures, the optional
 * {@code .bbmodel} source, the thumbnail under {@code .materione/thumbnails/},
 * the {@code .materione/model-links.json} relationship entry, and patches the
 * owner field — all-or-nothing. Any failure restores every touched file and
 * the owner document's journal checkpoint, so a rejected save leaves no
 * partial model changes behind. The staging and rollback mechanics are shared
 * with the field save with assets ({@link AssetStaging}).
 *
 * <p>Core owns the filesystem mutation (D1); the host layer queues the
 * pipeline reload and renders the error diagnostics.
 */
public final class ModelTransactionService {

    // Also bounds a single canonical document read by the summary projection.
    static final int MAX_TRANSACTION_BYTES = AssetStaging.MAX_BYTES;

    /** Pack content: everything the compiler ships to clients. */
    private static final Pattern SHIPPED_ASSET =
            Pattern.compile("assets/[A-Za-z0-9_./ -]+");

    /**
     * Authoring metadata: the model render cached beside `model-links.json` so
     * the editor can show a model without booting Blockbench. `.materione/` is
     * never walked by the compiler, so this stays out of the client pack.
     */
    private static final Pattern THUMBNAIL_ASSET =
            Pattern.compile("\\.materione/thumbnails/[A-Za-z0-9_./-]+\\.png");

    private final DocumentStore documents;
    private final Path packsRoot;
    private final AuditLog audit;

    public ModelTransactionService(DocumentStore documents, Path packsRoot, AuditLog audit) {
        this.documents = documents;
        this.packsRoot = packsRoot;
        this.audit = audit;
    }

    /**
     * Runs one transaction. Returns the updated owner document, or null when
     * the transaction attached no owner (a pure asset save). Throws
     * {@link DocumentConflictException} / {@link IllegalArgumentException} /
     * {@link IllegalStateException} exactly like the editor op always did;
     * after any throw nothing partial remains.
     */
    public DocumentStore.Document save(ModelTransaction spec) throws IOException {
        return save(spec, () -> { });
    }

    /**
     * Same as {@link #save(ModelTransaction)} with a hook invoked after the
     * staged assets are promoted but before the owner document is patched —
     * the seam fault-injection tests use to prove rollback.
     */
    public DocumentStore.Document save(ModelTransaction spec, Runnable afterAssetPromotion)
            throws IOException {
        synchronized (AssetStaging.LOCK) {
            String pack = spec.pack();
            Path packRoot = AssetStaging.packRoot(packsRoot, pack);
            String ownerPath = spec.ownerPath();
            boolean createOwner = spec.createOwner();
            boolean attachOwner = createOwner || !ownerPath.isBlank();
            int expectedRevision = attachOwner && !createOwner ? spec.expectedRevision() : -1;
            String modelReference = attachOwner ? spec.modelReference() : "";
            if (attachOwner && modelReference.isBlank()) {
                throw new IllegalArgumentException("missing field 'modelRef'");
            }
            if (attachOwner && !modelReference.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid model reference: " + modelReference);
            }
            List<String> field = attachOwner && !createOwner ? spec.ownerField() : List.of();
            if (attachOwner && !createOwner && field.isEmpty()) {
                throw new IllegalArgumentException("model owner field is required");
            }
            List<AssetStaging.StagedAsset> assets = AssetStaging.stage(
                    packRoot, spec.assets(),
                    relative -> SHIPPED_ASSET.matcher(relative).matches()
                            || THUMBNAIL_ASSET.matcher(relative).matches());
            if (createOwner) {
                if (spec.ownerId().isBlank()) {
                    throw new IllegalArgumentException("missing field 'ownerId'");
                }
                // Blocks and furniture own models too; items stay the
                // default so older clients keep working.
                String ownerType = spec.ownerType();
                if (ownerType.isBlank()) {
                    ownerType = "items";
                }
                if (!Scaffolds.SCAFFOLD_TYPES.contains(ownerType)) {
                    throw new IllegalArgumentException("unknown owner type: " + ownerType);
                }
                ownerPath = ownerType + "/" + spec.ownerId() + ".yml";
                if (Files.exists(packsRoot.resolve(pack).resolve(ownerPath))) {
                    throw new IllegalStateException("document already exists: " + ownerPath);
                }
            } else if (attachOwner) {
                DocumentStore.Document owner = documents.read(pack, ownerPath);
                if (owner.revision() != expectedRevision) {
                    throw new DocumentConflictException(
                            "expected revision " + expectedRevision
                                    + " but current revision is " + owner.revision(),
                            expectedRevision,
                            owner.revision());
                }
            }
            if (attachOwner) {
                String sourcePath = spec.sourcePath();
                if (!sourcePath.isBlank()) {
                    Path expectedSource = packsRoot.resolve(pack).resolve(sourcePath).normalize();
                    boolean included = assets.stream()
                            .anyMatch(asset -> asset.target().equals(expectedSource));
                    if (!sourcePath.endsWith(".bbmodel") || !included) {
                        throw new IllegalArgumentException(
                                "model source must name a .bbmodel in this transaction");
                    }
                }
                String linkField = createOwner
                        ? spec.ownerId() + ".model"
                        : String.join(".", field);
                assets = new ArrayList<>(assets);
                assets.add(relationship(
                        packRoot, ownerPath, linkField,
                        modelReference, sourcePath));
            }

            Map<Path, AssetStaging.Backup> backups = new LinkedHashMap<>();
            DocumentStore.Checkpoint ownerCheckpoint =
                    attachOwner ? documents.checkpoint(pack, ownerPath) : null;
            try {
                for (AssetStaging.StagedAsset asset : assets) {
                    backups.put(asset.target(), AssetStaging.Backup.capture(asset.target()));
                    Files.createDirectories(asset.target().getParent());
                    Files.write(asset.temp(), asset.bytes());
                }
                for (AssetStaging.StagedAsset asset : assets) {
                    AssetStaging.move(asset.temp(), asset.target());
                }
                afterAssetPromotion.run();
                String actor = spec.actor();
                DocumentStore.Document updated = null;
                if (createOwner) {
                    if (spec.ownerName().isBlank()) {
                        throw new IllegalArgumentException("missing field 'ownerName'");
                    }
                    String scaffold = Scaffolds.forPath(ownerPath, spec.ownerId());
                    String content = LosslessYaml.parse(scaffold)
                            .withScalar(spec.ownerName(), spec.ownerId(), "display_name")
                            .withScalar(modelReference, spec.ownerId(), "model")
                            .save();
                    updated = documents.createResource(
                            pack, ownerPath, content, false, actor);
                } else if (attachOwner) {
                    updated = documents.setScalar(
                            pack, ownerPath, expectedRevision, field, modelReference, actor);
                }
                audit.record(
                        actor,
                        "authoring.saveModel",
                        pack + (attachOwner ? "/" + ownerPath : "")
                                + " (" + assets.size() + " assets)");
                return updated;
            } catch (Exception failure) {
                AssetStaging.restore(backups);
                if (ownerCheckpoint != null) {
                    documents.restore(ownerCheckpoint);
                }
                throw failure;
            } finally {
                for (AssetStaging.StagedAsset asset : assets) {
                    Files.deleteIfExists(asset.temp());
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private AssetStaging.StagedAsset relationship(
            Path packRoot,
            String ownerPath,
            String field,
            String modelReference,
            String sourcePath) throws IOException {
        Path target = packRoot.resolve(".materione").resolve("model-links.json");
        AssetStaging.rejectSymlinkPath(packRoot, target);
        Map<String, Object> manifest = new LinkedHashMap<>();
        Map<String, Object> links = new LinkedHashMap<>();
        if (Files.isRegularFile(target)) {
            try {
                manifest = ModelLinksJson.parse(Files.readString(target));
                if (manifest.get("links") instanceof Map<?, ?> existing) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> links0 = (Map<String, Object>) existing;
                    links = links0;
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(ModelLinksJson.INVALID, e);
            }
        }
        manifest.put("version", 1);
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("model", modelReference);
        if (!sourcePath.isBlank()) {
            link.put("source", sourcePath);
        }
        links.put(ownerPath + "#" + field, link);
        manifest.put("links", links);
        byte[] bytes = (ModelLinksJson.serialize(manifest) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        return new AssetStaging.StagedAsset(target, AssetStaging.tempFor(target), bytes);
    }
}
