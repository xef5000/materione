package dev.materione.api.authoring;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Content and document authoring (api.md §3.3): every operation that reads
 * or mutates content packs, folders, documents and parameters — on disk
 * (D1), through lossless YAML transactions that preserve comments and
 * formatting byte-for-byte (master §5.6), with revision journals, audit
 * entries, and a pipeline trigger after mutation (D6 — never a
 * direct-to-runtime shortcut).
 *
 * <p>Fail-soft: domain errors (syntax {@code MTR-0007}, revision conflicts
 * {@code MTR-0034}, missing files {@code MTR-0009}) return {@link ApiResult}
 * diagnostics; nothing throws for content problems.
 */
public interface AuthoringApi {

    /** Every enabled pack under {@code content/packs/}. */
    ApiResult<List<PackInfo>> listPacks();

    /** Scaffolds {@code content/packs/<packId>/pack.yml} (idempotent shape of {@code edit.createPack}). */
    ApiResult<PackInfo> createPack(ActorContext actor, String packId);

    /** Organizational subdirectories inside the pack (all content types). */
    ApiResult<List<String>> listFolders(String pack);

    ApiResult<Void> createFolder(ActorContext actor, String pack, String path);

    ApiResult<Void> deleteFolder(ActorContext actor, String pack, String path);

    ApiResult<Void> moveFolder(ActorContext actor, String pack, String fromPath, String toPath);

    /** Resources by pack, content type and/or substring query. */
    ApiResult<List<ResourceRef>> listResources(ResourceFilter filter);

    /**
     * One document: raw YAML, parsed tree (null when it does not parse),
     * current revision, sha256, and server-computed metadata (e.g. the
     * allocated glyph codepoints the server holds for this file).
     */
    ApiResult<DocumentView> getDocument(String pack, String path);

    /** The per-document revision journal ({@code storage/revisions/}). */
    ApiResult<List<RevisionInfo>> getHistory(String pack, String path, int limit);

    /** Creates a YAML document from the type's curated scaffold ({@code edit.create}). */
    ApiResult<DocumentView> createDocument(ActorContext actor, CreateDocumentRequest request);

    /**
     * Transactional onboarding/resource creation: optional pack creation +
     * scaffold + initial fields ({@code authoring.createResource}) — one
     * revision, one audit entry, one reload.
     */
    ApiResult<DocumentView> createResource(ActorContext actor, CreateResourceRequest request);

    /** Clones an existing resource under a new id ({@code authoring.duplicateResource}). */
    ApiResult<DocumentView> duplicateResource(ActorContext actor, DuplicateResourceRequest request);

    /** Revision-guarded raw YAML write ({@code edit.put}). */
    ApiResult<DocumentView> putDocument(ActorContext actor, PutDocumentRequest request);

    /**
     * Studio commit: {@link #putDocument} semantics, audited under its own
     * operation name ({@code authoring.commit}) — the audit trail records
     * what the actor invoked, and a studio commit is not a raw edit.
     */
    ApiResult<DocumentView> commitDocument(ActorContext actor, PutDocumentRequest request);

    /** Lossless single-field or scalar-list patch preserving comments and formatting ({@code edit.setScalar}). */
    ApiResult<DocumentView> setScalar(ActorContext actor, SetScalarRequest request);

    /** Atomic batch of field patches — 1 revision, 1 audit, 1 reload ({@code authoring.setFields}). */
    ApiResult<DocumentView> setFields(ActorContext actor, SetFieldsRequest request);

    /** Rename/move a document across folders with truthful {@code move-from} lineage ({@code edit.move}). */
    ApiResult<DocumentView> moveDocument(ActorContext actor, MoveDocumentRequest request);

    /** Revision-guarded deletion ({@code edit.delete}). */
    ApiResult<Void> deleteDocument(ActorContext actor, DeleteDocumentRequest request);

    /**
     * Atomic Blockbench save-back ({@code authoring.saveModel}): stages model
     * JSON, textures, the optional {@code .bbmodel} source, the thumbnail
     * under {@code .materione/thumbnails/}, the {@code .materione/model-links.json}
     * relationship entry, and patches the owner field — all-or-nothing.
     */
    ApiResult<SaveModelResult> saveModelTransaction(ActorContext actor, SaveModelRequest request);

    /** Dry-run syntax validation on unsaved content; never touches disk ({@code validate.dryRun}). */
    ApiResult<Void> validateDraft(ValidateDraftRequest request);

    /**
     * Content graph queries: "where is this used?", broken references.
     * The graph is the last completed pipeline run's view.
     */
    ApiResult<GraphQueryResult> queryGraph(GraphQuery query);

    /**
     * Document change feed — external edits (file watcher) and API saves
     * alike. Returns an {@link AutoCloseable} subscription handle.
     */
    AutoCloseable subscribeChanges(Consumer<DocumentChangeEvent> listener);

    // ------------------------------------------------------------------

    /** One pack directory under {@code content/packs/}. */
    record PackInfo(String id) {}

    /** One resource keyed inside a document ({@code pack:type/path:resourceId}). */
    record ResourceRef(String pack, String type, String path, String resourceId) {}

    /** {@link #listResources} filter — null pack/type means all; query is a case-insensitive substring. */
    record ResourceFilter(String pack, String type, String query) {
        public static ResourceFilter all() {
            return new ResourceFilter(null, null, null);
        }
    }

    /**
     * A document as one caller sees it. {@code parsed} is the lossless
     * tree as plain maps (null when the YAML does not parse); {@code computed}
     * carries server-computed metadata for this file (allocated glyph
     * codepoints, …) — empty when none applies.
     */
    record DocumentView(
            String pack,
            String path,
            String raw,
            Map<String, Object> parsed,
            int revision,
            String sha256,
            Map<String, Object> computed) {}

    /** One revision journal entry. */
    record RevisionInfo(int revision, long timestampMs, String actor, String action, String sha256) {}

    /** {@link #createDocument}: content null → the type's scaffold; displayName applied when the scaffold has one. */
    record CreateDocumentRequest(
            String pack, String path, String resourceId, String displayName, String content) {}

    /** {@link #createResource}: type defaults to {@code items}; folder is a subdirectory inside the type. */
    record CreateResourceRequest(
            String pack,
            String resourceId,
            String displayName,
            String type,
            String folder,
            boolean createPack) {}

    /** {@link #duplicateResource}: copies {@code fromPath}'s bytes re-keyed to {@code resourceId}. */
    record DuplicateResourceRequest(String pack, String fromPath, String resourceId, String folder) {}

    /** {@link #putDocument}: expectedRevision -1 accepts any current revision. */
    record PutDocumentRequest(String pack, String path, int expectedRevision, String content) {}

    /** {@link #setScalar}: one lossless field patch; scalar lists patch as a whole. */
    record SetScalarRequest(
            String pack, String path, int expectedRevision, List<String> field, Object value) {}

    /** One field patch inside a {@link SetFieldsRequest} batch. */
    record FieldEdit(List<String> field, Object value) {}

    /** {@link #setFields}: atomic batch of scalar/structured patches — one revision, one audit, one reload. */
    record SetFieldsRequest(
            String pack, String path, int expectedRevision, List<FieldEdit> edits) {}

    /** {@link #moveDocument}: moves (and re-keys the journal of) one document. */
    record MoveDocumentRequest(String pack, String fromPath, int expectedRevision, String toPath) {}

    /** {@link #deleteDocument}: revision-guarded; the journal keeps the history. */
    record DeleteDocumentRequest(String pack, String path, int expectedRevision) {}

    /** One binary staged by {@link #saveModelTransaction}: a shipped asset or a thumbnail. */
    record ModelAsset(String path, byte[] bytes) {}

    /**
     * {@link #saveModelTransaction}. When {@code createOwner} is true the
     * owner document is scaffolded (ownerType defaults to {@code items});
     * otherwise {@code ownerPath} + {@code ownerField} name the existing
     * field the model reference is written into, revision-guarded by
     * {@code expectedRevision}. {@code sourcePath} must name a
     * {@code .bbmodel} among the assets when present.
     */
    record SaveModelRequest(
            String pack,
            String ownerPath,
            boolean createOwner,
            String ownerId,
            String ownerType,
            String ownerName,
            String modelReference,
            List<String> ownerField,
            int expectedRevision,
            String sourcePath,
            List<ModelAsset> assets) {}

    /** {@link #saveModelTransaction} outcome; revision/raw null when no owner document was attached. */
    record SaveModelResult(String ownerPath, Integer revision, String raw) {}

    /** {@link #validateDraft}: unsaved YAML, validated without touching disk. */
    record ValidateDraftRequest(String content) {}

    /**
     * {@link #queryGraph}: with a target, everything referencing it; with
     * {@link #unresolved()}, the broken-reference list of the last run.
     */
    record GraphQuery(NamespacedId target, boolean unresolvedOnly) {
        public static GraphQuery referencesTo(NamespacedId target) {
            return new GraphQuery(target, false);
        }

        public static GraphQuery unresolved() {
            return new GraphQuery(null, true);
        }
    }

    /** {@link #queryGraph} result. */
    record GraphQueryResult(List<ResourceRef> referencing, List<UnresolvedReference> unresolved) {
        /** One edge whose target does not exist (a broken reference). */
        record UnresolvedReference(
                NamespacedId from, NamespacedId missingTarget, String edgeType, Path sourceFile) {}
    }

    /** A document changed on disk or through the API (watcher feed; {@code push.document.changed}). */
    record DocumentChangeEvent(String pack, String path, boolean deleted) {}
}
