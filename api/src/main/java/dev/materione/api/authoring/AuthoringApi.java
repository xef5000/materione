package dev.materione.api.authoring;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
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
     * A bounded page of server-computed catalog metadata. Implementations
     * without a summary projection retain compatibility by returning identity
     * rows with unknown health; they never fetch each document individually.
     */
    default ApiResult<ResourceSummaryPage> listResourceSummaries(
            ResourceFilter filter, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_SUMMARY_PAGE_SIZE) {
            return ApiResult.fail(new Diagnostic(
                    DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                    "summary page requires offset >= 0 and limit between 1 and "
                            + MAX_SUMMARY_PAGE_SIZE,
                    null, null, null));
        }
        ApiResult<List<ResourceRef>> listed = listResources(filter);
        if (!listed.ok()) {
            return ApiResult.fail(listed.diagnostics());
        }
        List<ResourceRef> refs = listed.value() == null ? List.of() : listed.value();
        int start = Math.min(offset, refs.size());
        int end = (int) Math.min((long) start + limit, refs.size());
        List<ResourceSummary> summaries = refs.subList(start, end).stream()
                .map(ref -> new ResourceSummary(
                        ref.pack(), ref.type(), ref.path(), ref.resourceId(), ref.resourceId(),
                        null, null, ResourceHealth.UNKNOWN,
                        "summary metadata is unavailable from this implementation"))
                .toList();
        Integer nextOffset = end < refs.size() ? end : null;
        return ApiResult.ok(new ResourceSummaryPage(summaries, refs.size(), nextOffset));
    }

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

    /**
     * Every template of every pack, with what it sets, which content types it
     * serves and which resources inherit it ({@code authoring.templates}).
     * Read-only; derived from the authored documents, never stored.
     */
    ApiResult<List<TemplateInfo>> listTemplates();

    /**
     * Materialises a resource's inherited values into the resource and drops
     * its {@code template:} link ({@code authoring.detachTemplate}) — one
     * revision, resolved by the pipeline's own resolver at write time.
     */
    ApiResult<DocumentView> detachTemplate(ActorContext actor, DetachTemplateRequest request);

    /**
     * Writes a new {@code templates/} document holding the chosen authored
     * fields of an existing resource ({@code authoring.saveAsTemplate}).
     */
    ApiResult<DocumentView> saveAsTemplate(ActorContext actor, SaveAsTemplateRequest request);

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

    /**
     * Atomic batch of field patches — 1 revision, 1 audit, 1 reload ({@code authoring.setFields}),
     * optionally with binaries committed in the same transaction ({@link SetFieldsRequest#assets()}).
     */
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
     * Typed, bounded "where is this used?" over the authored documents of every
     * pack. Never throws for content problems; what it could not read is
     * reported in {@link ReferenceCoverage}, not hidden.
     */
    ApiResult<ReferenceResult> findReferences(ReferenceQuery query);

    /**
     * Revision-checked, all-or-none authoring across several documents
     * ({@code authoring.bulk}): patches, new resources and duplicates are
     * validated together and written together, or nothing is written. With
     * {@code dryRun} the impact summary is returned and disk is untouched.
     */
    ApiResult<BulkResult> bulk(ActorContext actor, BulkRequest request);

    /**
     * Document change feed — external edits (file watcher) and API saves
     * alike. Returns an {@link AutoCloseable} subscription handle.
     */
    AutoCloseable subscribeChanges(Consumer<DocumentChangeEvent> listener);

    /**
     * The GUI builder's typed surface (gui.md §7, §10): layer edits, item
     * customization, render preview and validation, built on this API's own
     * transactions (api.md §3.9).
     */
    dev.materione.api.gui.GuiAuthoringApi gui();

    // ------------------------------------------------------------------

    /** One pack directory under {@code content/packs/}. */
    record PackInfo(String id) {}

    /** One resource keyed inside a document ({@code pack:type/path:resourceId}). */
    record ResourceRef(String pack, String type, String path, String resourceId) {}

    /** Safe preview identity; the path is a server-resolved readable asset path. */
    record ResourceVisual(String pack, String path, VisualKind kind) {}

    /**
     * What the editor needs to draw a resource's model live: either a
     * readable model asset ({@code path}, under {@code pack}) or the inline
     * model the type compiles for it ({@code model}, texture references
     * resolved against {@code pack}). Exactly one of path/model is set.
     */
    record ResourceRender(String pack, String path, java.util.Map<String, Object> model) {
        public ResourceRender {
            if ((path == null) == (model == null)) {
                throw new IllegalArgumentException("render needs exactly one of path or model");
            }
            model = model == null ? null : java.util.Map.copyOf(model);
        }
    }

    enum VisualKind {
        TEXTURE,
        MODEL_THUMBNAIL
    }

    enum ResourceHealth {
        OK,
        WARNING,
        ERROR,
        UNKNOWN
    }

    /** Read-only catalog projection over one canonical resource. */
    record ResourceSummary(
            String pack,
            String type,
            String path,
            String resourceId,
            String name,
            String material,
            ResourceVisual visual,
            ResourceHealth health,
            String healthMessage,
            ResourceRender render) {

        public ResourceSummary {
            name = name == null || name.isBlank() ? resourceId : name;
            health = health == null ? ResourceHealth.UNKNOWN : health;
        }

        /** Source-compatible constructor for summaries without a live render. */
        public ResourceSummary(
                String pack, String type, String path, String resourceId, String name,
                String material, ResourceVisual visual, ResourceHealth health, String healthMessage) {
            this(pack, type, path, resourceId, name, material, visual, health, healthMessage, null);
        }
    }

    /** One bounded page; {@code nextOffset} is absent when all rows were returned. */
    record ResourceSummaryPage(
            List<ResourceSummary> resources,
            int total,
            Integer nextOffset) {

        public ResourceSummaryPage {
            resources = resources == null ? List.of() : List.copyOf(resources);
        }
    }

    /** Maximum result size accepted by every {@link #listResourceSummaries} implementation. */
    int MAX_SUMMARY_PAGE_SIZE = 200;

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

    /**
     * {@link #createResource}: type defaults to {@code items}; folder is a
     * subdirectory inside the type. With a {@code template} the new document
     * is just {@code template:} plus the display name — the template supplies
     * everything else, so nothing is copied that could drift from it. For
     * {@code type=templates}, {@code serves} names the type directory the new
     * template is for (it picks the starter field).
     */
    record CreateResourceRequest(
            String pack,
            String resourceId,
            String displayName,
            String type,
            String folder,
            boolean createPack,
            String template,
            String serves) {

        /** A resource from the type's curated scaffold. */
        public CreateResourceRequest(
                String pack, String resourceId, String displayName, String type,
                String folder, boolean createPack) {
            this(pack, resourceId, displayName, type, folder, createPack, null, null);
        }

        /** A resource that inherits from {@code template}. */
        public CreateResourceRequest(
                String pack, String resourceId, String displayName, String type,
                String folder, boolean createPack, String template) {
            this(pack, resourceId, displayName, type, folder, createPack, template, null);
        }
    }

    /** One template as the editor lists it ({@link #listTemplates}). */
    record TemplateInfo(
            String pack,
            String path,
            String id,
            String parent,
            Map<String, Object> definition,
            List<String> serves,
            List<ResourceRef> usedBy) {
        public TemplateInfo {
            definition = definition == null ? Map.of() : Map.copyOf(definition);
            serves = serves == null ? List.of() : List.copyOf(serves);
            usedBy = usedBy == null ? List.of() : List.copyOf(usedBy);
        }
    }

    /** {@link #detachTemplate}: {@code resourceId} is the root inside the document. */
    record DetachTemplateRequest(String pack, String path, String resourceId, int expectedRevision) {}

    /**
     * {@link #saveAsTemplate}: copies {@code fields} (top-level authored keys)
     * of {@code resourceId} in {@code fromPath} into {@code templates/<templateId>.yml}.
     */
    record SaveAsTemplateRequest(
            String pack, String fromPath, String resourceId, String templateId, List<String> fields) {}

    /** {@link #duplicateResource}: copies {@code fromPath}'s bytes re-keyed to {@code resourceId}. */
    record DuplicateResourceRequest(String pack, String fromPath, String resourceId, String folder) {}

    /** {@link #putDocument}: expectedRevision -1 accepts any current revision. */
    record PutDocumentRequest(String pack, String path, int expectedRevision, String content) {}

    /** {@link #setScalar}: one lossless field patch; scalar lists patch as a whole. */
    record SetScalarRequest(
            String pack, String path, int expectedRevision, List<String> field, Object value) {}

    /** One field patch inside a {@link SetFieldsRequest} batch. */
    record FieldEdit(List<String> field, Object value, boolean remove) {

        /** Sets {@code field} to {@code value}. */
        public FieldEdit(List<String> field, Object value) {
            this(field, value, false);
        }

        /**
         * Removes {@code field} from the document (lossless; an absent key is a
         * no-op) — how the editor resets a field to its inherited value.
         */
        public static FieldEdit removal(List<String> field) {
            return new FieldEdit(field, null, true);
        }
    }

    /**
     * {@link #setFields}: atomic batch of scalar/structured patches — one revision, one audit, one reload.
     * {@code assets} are binaries committed in the same transaction (a GUI's paint PNG, say):
     * they follow the asset store's {@code assets/} path rules, and any failure keeps neither
     * the edits nor the binaries.
     */
    record SetFieldsRequest(
            String pack, String path, int expectedRevision, List<FieldEdit> edits, List<ModelAsset> assets) {

        public SetFieldsRequest {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }

        /** Field edits only, the common studio save. */
        public SetFieldsRequest(String pack, String path, int expectedRevision, List<FieldEdit> edits) {
            this(pack, path, expectedRevision, edits, List.of());
        }
    }

    /** {@link #moveDocument}: moves (and re-keys the journal of) one document. */
    record MoveDocumentRequest(String pack, String fromPath, int expectedRevision, String toPath) {}

    /** {@link #deleteDocument}: revision-guarded; the journal keeps the history. */
    record DeleteDocumentRequest(String pack, String path, int expectedRevision) {}

    /** One binary staged by {@link #saveModelTransaction} or {@link #setFields}: a shipped asset (or, for models, a thumbnail). */
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

    /** What a {@link ReferenceQuery} names. */
    enum ReferenceKind {
        /** An asset file: {@code target} is its pack-relative path (e.g. {@code assets/textures/gem.png}). */
        ASSET,
        /** A content resource: {@code target} is its id. */
        RESOURCE
    }

    /** {@link #findReferences}: {@code pack} is the pack that owns the target. */
    record ReferenceQuery(ReferenceKind kind, String pack, String target) {}

    /** One authored scalar that names the target. */
    record ReferenceHit(ResourceRef resource, List<String> field, String value) {}

    /**
     * How much of the project the answer covers. {@code complete} is true only
     * when every document was read and none was unparsable or skipped; the
     * {@code notScanned} sentences name what this query never looks at.
     */
    record ReferenceCoverage(
            int documentsScanned,
            int unreadable,
            boolean truncated,
            boolean complete,
            List<String> notScanned) {
        public ReferenceCoverage {
            notScanned = notScanned == null ? List.of() : List.copyOf(notScanned);
        }
    }

    /** {@link #findReferences} result. */
    record ReferenceResult(List<ReferenceHit> hits, ReferenceCoverage coverage) {
        public ReferenceResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    /** What one {@link BulkOperation} does. */
    enum BulkKind {
        /** Patch the existing document at {@code path}, guarded by {@code expectedRevision}. */
        SET,
        /** Create a new resource of {@code type} with {@code resourceId}, then apply {@code edits}. */
        CREATE,
        /** Copy the document at {@code fromPath} under {@code resourceId}, then apply {@code edits}. */
        DUPLICATE
    }

    /** One operation of a {@link BulkRequest}; fields irrelevant to the kind are null. */
    record BulkOperation(
            BulkKind kind,
            String path,
            int expectedRevision,
            String type,
            String folder,
            String resourceId,
            String displayName,
            String fromPath,
            List<FieldEdit> edits) {
        public BulkOperation {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }

    /** {@link #bulk}: operations on one pack. */
    record BulkRequest(String pack, List<BulkOperation> operations, boolean dryRun) {}

    /** One field's change inside a {@link BulkDocumentResult}. */
    record BulkFieldChange(List<String> field, Object before, Object after) {}

    /** One document's part of a {@link BulkResult}; {@code revision} is the one it has (dry run: would get). */
    record BulkDocumentResult(
            String path, BulkKind kind, int revision, boolean unchanged, List<BulkFieldChange> changes) {
        public BulkDocumentResult {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /** {@link #bulk} outcome; {@code applied} is false for a dry run. */
    record BulkResult(boolean applied, List<BulkDocumentResult> documents) {
        public BulkResult {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    /** The most operations one {@link #bulk} call accepts. */
    int MAX_BULK_OPERATIONS = 50;
}
