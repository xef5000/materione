package dev.materione.core.authoring;

import dev.materione.api.ApiResult;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.content.ContentType;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.AssetStore;
import dev.materione.core.document.DocumentConflictException;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.Documents;
import dev.materione.core.document.FieldsTransactionService;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.ModelTransaction;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.document.Scaffolds;
import dev.materione.core.pipeline.PackLayout;
import dev.materione.core.pipeline.TemplateResolver;
import dev.materione.core.pipeline.graph.ContentGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The {@link AuthoringApi} implementation over the document store (api.md
 * §3.3): every operation the web editor's document RPC surface performs,
 * expressed as typed, actor-attributed, fail-soft calls. Mutations write
 * canonical files (D1), journal a revision, audit, and then fire the
 * {@link ReloadHook} with exactly the scope the editor op queues today —
 * targeted file sets for document saves, pack-wide for assets, model
 * transactions and folder moves.
 *
 * <p>Diagnostics carry the same MTR codes the wire always used: conflicts
 * {@code MTR-0034}, syntax {@code MTR-0007}, missing files {@code MTR-0009},
 * bad requests {@code MTR-0008}, model-transaction rejects {@code MTR-0035},
 * handler failures {@code MTR-0011}.
 */
public final class AuthoringApiImpl implements AuthoringApi {

    private final DocumentStore documents;
    private final AuditLog audit;
    private final Path packsRoot;
    private final ModelTransactionService modelTransactions;
    private final FieldsTransactionService fieldTransactions;
    private final DocumentMetadataComputer computedMeta;
    private final Supplier<ContentGraph> graph;
    private final ReloadHook reloadHook;
    private final ResourceSummaryComputer summaryComputer;
    private final Function<AuthoringApi, GuiAuthoringApi> guiFactory;
    private volatile GuiAuthoringApi guiApi;
    private final List<Consumer<DocumentChangeEvent>> changeListeners = new CopyOnWriteArrayList<>();

    public AuthoringApiImpl(
            DocumentStore documents,
            AuditLog audit,
            Path packsRoot,
            ModelTransactionService modelTransactions,
            DocumentMetadataComputer computedMeta,
            Supplier<ContentGraph> graph,
            ReloadHook reloadHook) {
        this(documents, audit, packsRoot, modelTransactions, computedMeta, graph,
                reloadHook, null, List::of);
    }

    public AuthoringApiImpl(
            DocumentStore documents,
            AuditLog audit,
            Path packsRoot,
            ModelTransactionService modelTransactions,
            DocumentMetadataComputer computedMeta,
            Supplier<ContentGraph> graph,
            ReloadHook reloadHook,
            AssetStore assets,
            Supplier<List<ContentType>> contentTypes) {
        this(documents, audit, packsRoot, modelTransactions, computedMeta, graph, reloadHook,
                assets, contentTypes, null);
    }

    /**
     * @param gui builds the GUI surface over this implementation (gui.md §10 #8); the
     *            content module supplies it, and a null factory reports the surface as
     *            unavailable. Built on first use, so it may reference this instance.
     */
    public AuthoringApiImpl(
            DocumentStore documents,
            AuditLog audit,
            Path packsRoot,
            ModelTransactionService modelTransactions,
            DocumentMetadataComputer computedMeta,
            Supplier<ContentGraph> graph,
            ReloadHook reloadHook,
            AssetStore assets,
            Supplier<List<ContentType>> contentTypes,
            Function<AuthoringApi, GuiAuthoringApi> gui) {
        this(documents, audit, packsRoot, modelTransactions, computedMeta, graph, reloadHook,
                assets, contentTypes, ResourceSummaryComputer.DEFAULT_PROJECTION_BUDGET_BYTES, gui);
    }

    AuthoringApiImpl(
            DocumentStore documents,
            AuditLog audit,
            Path packsRoot,
            ModelTransactionService modelTransactions,
            DocumentMetadataComputer computedMeta,
            Supplier<ContentGraph> graph,
            ReloadHook reloadHook,
            AssetStore assets,
            Supplier<List<ContentType>> contentTypes,
            long summaryProjectionBudgetBytes) {
        this(documents, audit, packsRoot, modelTransactions, computedMeta, graph, reloadHook,
                assets, contentTypes, summaryProjectionBudgetBytes, null);
    }

    AuthoringApiImpl(
            DocumentStore documents,
            AuditLog audit,
            Path packsRoot,
            ModelTransactionService modelTransactions,
            DocumentMetadataComputer computedMeta,
            Supplier<ContentGraph> graph,
            ReloadHook reloadHook,
            AssetStore assets,
            Supplier<List<ContentType>> contentTypes,
            long summaryProjectionBudgetBytes,
            Function<AuthoringApi, GuiAuthoringApi> gui) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.packsRoot = Objects.requireNonNull(packsRoot, "packsRoot");
        this.modelTransactions = Objects.requireNonNull(modelTransactions, "modelTransactions");
        this.fieldTransactions = new FieldsTransactionService(documents, packsRoot, audit);
        this.computedMeta = computedMeta;
        this.graph = graph == null ? () -> null : graph;
        this.summaryComputer = new ResourceSummaryComputer(
                documents, assets, contentTypes, summaryProjectionBudgetBytes);
        ReloadHook downstream = reloadHook == null ? (actor, pack, file) -> { } : reloadHook;
        this.reloadHook = (actor, pack, file) -> {
            summaryComputer.invalidate();
            downstream.afterChange(actor, pack, file);
        };
        this.guiFactory = gui;
    }

    @Override
    public GuiAuthoringApi gui() {
        GuiAuthoringApi current = guiApi;
        if (current == null) {
            synchronized (this) {
                current = guiApi;
                if (current == null) {
                    current = guiFactory == null
                            ? new UnavailableGuiAuthoring() : guiFactory.apply(this);
                    guiApi = current;
                }
            }
        }
        return current;
    }

    // ------------------------------------------------------------------
    // browsing

    @Override
    public ApiResult<List<PackInfo>> listPacks() {
        List<PackInfo> packs = new ArrayList<>();
        documents.listPacks().forEach(pack -> packs.add(new PackInfo(pack.id())));
        return ApiResult.ok(packs);
    }

    @Override
    public ApiResult<PackInfo> createPack(ActorContext actor, String packId) {
        try {
            documents.createPack(packId, actorId(actor));
            audit.record(actorId(actor), "edit.createPack", packId);
            return ApiResult.ok(new PackInfo(packId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<List<String>> listFolders(String pack) {
        try {
            return ApiResult.ok(documents.listFolders(pack));
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<Void> createFolder(ActorContext actor, String pack, String path) {
        try {
            documents.createFolder(pack, path);
            audit.record(actorId(actor), "edit.createFolder", pack + "/" + path);
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<Void> deleteFolder(ActorContext actor, String pack, String path) {
        try {
            documents.deleteFolder(pack, path);
            audit.record(actorId(actor), "edit.deleteFolder", pack + "/" + path);
            return ApiResult.ok(null);
        } catch (NoSuchFileException e) {
            return missing("no such folder");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return new ApiResult<Void>(false, null, List.of(new Diagnostic(
                    DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                    e.getMessage(), null, null, "empty the folder first")));
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<Void> moveFolder(ActorContext actor, String pack, String fromPath, String toPath) {
        try {
            documents.moveFolder(pack, fromPath, toPath, actorId(actor));
            audit.record(actorId(actor), "edit.moveFolder", pack + "/" + fromPath + " -> " + toPath);
            reloadHook.afterChange(actor, pack, packsRoot.resolve(pack));
            return ApiResult.ok(null);
        } catch (DocumentConflictException e) {
            return conflict(e, "reload the pack and try again");
        } catch (NoSuchFileException e) {
            return missing("no such folder");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<List<ResourceRef>> listResources(ResourceFilter filter) {
        try {
            ResourceFilter safe = filter == null ? ResourceFilter.all() : filter;
            List<ResourceRef> refs = new ArrayList<>();
            for (DocumentStore.ResourceRef ref
                    : documents.listResources(safe.pack(), safe.type(), safe.query())) {
                refs.add(new ResourceRef(ref.pack(), ref.type(), ref.path(), ref.resourceId()));
            }
            return ApiResult.ok(refs);
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<ResourceSummaryPage> listResourceSummaries(
            ResourceFilter filter, int offset, int limit) {
        try {
            return ApiResult.ok(summaryComputer.summarize(filter, offset, limit));
        } catch (ResourceSummaryComputer.ProjectionBudgetExceededException e) {
            return new ApiResult<>(false, null, List.of(new Diagnostic(
                    DiagnosticCodes.AUTHORING_SUMMARY_BUDGET_EXCEEDED, Severity.WARN,
                    e.getMessage(), null, null,
                    "The canonical files are unchanged and remain available through the full resource listing.")));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<DocumentView> getDocument(String pack, String path) {
        try {
            DocumentStore.Document document = documents.read(pack, path);
            return ApiResult.ok(view(document));
        } catch (NoSuchFileException e) {
            return missing("no such document");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<List<RevisionInfo>> getHistory(String pack, String path, int limit) {
        try {
            List<RevisionInfo> revisions = new ArrayList<>();
            for (DocumentStore.Revision revision : documents.history(pack, path, limit)) {
                revisions.add(new RevisionInfo(
                        revision.rev(), revision.timestampMs(), revision.actor(),
                        revision.action(), revision.sha256()));
            }
            return ApiResult.ok(revisions);
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    // ------------------------------------------------------------------
    // writes

    @Override
    public ApiResult<DocumentView> putDocument(ActorContext actor, PutDocumentRequest request) {
        return writeDocument(actor, request, "edit.put",
                "re-read the document and re-apply your change", true);
    }

    @Override
    public ApiResult<DocumentView> commitDocument(ActorContext actor, PutDocumentRequest request) {
        return writeDocument(actor, request, "authoring.commit",
                "re-read the item and re-apply your changes", false);
    }

    private ApiResult<DocumentView> writeDocument(
            ActorContext actor, PutDocumentRequest request, String operation,
            String conflictHint, boolean auditConflicts) {
        try {
            int revision = documents.write(
                    request.pack(), request.path(), request.expectedRevision(),
                    request.content(), actorId(actor));
            audit.record(actorId(actor), operation, request.pack() + "/" + request.path());
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), request.path()));
            return ApiResult.ok(new DocumentView(
                    request.pack(), request.path(), request.content(), parsed(request.content()),
                    revision, Documents.sha256(request.content()), Map.of()));
        } catch (DocumentConflictException e) {
            if (auditConflicts) {
                audit.record(actorId(actor), operation + ".conflict",
                        request.pack() + "/" + request.path());
            }
            return conflict(e, conflictHint);
        } catch (IllegalArgumentException e) {
            // syntax gate or path validation — the caller shows it inline
            return new ApiResult<DocumentView>(false, null, List.of(new Diagnostic(
                    DiagnosticCodes.CONTENT_PARSE_FAILED, Severity.ERROR,
                    e.getMessage(), null, null, null)));
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<DocumentView> setScalar(ActorContext actor, SetScalarRequest request) {
        try {
            DocumentStore.Document document = documents.setScalar(
                    request.pack(), request.path(), request.expectedRevision(),
                    request.field(), request.value(), actorId(actor));
            audit.record(actorId(actor), "edit.setScalar",
                    request.pack() + "/" + request.path());
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), request.path()));
            return ApiResult.ok(view(document));
        } catch (DocumentConflictException e) {
            return conflict(e, "re-read the document and retry");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<DocumentView> setFields(ActorContext actor, SetFieldsRequest request) {
        try {
            List<DocumentStore.FieldEdit> edits = new ArrayList<>();
            for (FieldEdit edit : request.edits()) {
                edits.add(new DocumentStore.FieldEdit(edit.field(), edit.value(), edit.remove()));
            }
            if (edits.isEmpty() && request.assets().isEmpty()) {
                throw new IllegalArgumentException("'edits' must be a non-empty array unless the save carries assets");
            }
            if (edits.size() > 256) {
                throw new IllegalArgumentException("too many edits in one save");
            }
            if (request.assets().isEmpty()) {
                DocumentStore.Document updated = documents.setScalars(
                        request.pack(), request.path(), request.expectedRevision(),
                        edits, actorId(actor));
                audit.record(actorId(actor), "authoring.setFields",
                        request.pack() + "/" + request.path() + " (" + edits.size() + " fields)");
                reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), request.path()));
                return ApiResult.ok(view(updated));
            }
            // Binaries ride the same transaction: all of the edits and assets, or none.
            List<ModelTransaction.Asset> assets = new ArrayList<>();
            for (ModelAsset asset : request.assets()) {
                assets.add(new ModelTransaction.Asset(asset.path(), asset.bytes()));
            }
            DocumentStore.Document updated = fieldTransactions.save(
                    actorId(actor), request.pack(), request.path(), request.expectedRevision(),
                    edits, assets);
            reloadHook.afterChange(actor, request.pack(), packsRoot.resolve(request.pack()));
            return ApiResult.ok(view(updated));
        } catch (DocumentConflictException e) {
            return conflict(e, "reopen the entry and re-apply your change");
        } catch (NoSuchFileException e) {
            return missing("no such document");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<DocumentView> createDocument(ActorContext actor, CreateDocumentRequest request) {
        try {
            String content = request.content() != null
                    ? request.content()
                    : Scaffolds.forPath(request.path(), request.resourceId());
            if (request.displayName() != null && !request.displayName().isBlank()
                    && content.contains("display_name:")) {
                content = LosslessYaml.parse(content)
                        .withScalar(displayName(request.displayName()), rootKey(content), "display_name")
                        .save();
            }
            documents.createDocument(request.pack(), request.path(), content, actorId(actor));
            audit.record(actorId(actor), "edit.create", request.pack() + "/" + request.path());
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), request.path()));
            return ApiResult.ok(view(documents.read(request.pack(), request.path())));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return new ApiResult<DocumentView>(false, null, List.of(new Diagnostic(
                    DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                    e.getMessage(), null, null, "open the existing document instead")));
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<DocumentView> createResource(ActorContext actor, CreateResourceRequest request) {
        try {
            String name = displayName(request.displayName());
            // The content browser creates inside the type tab (and folder)
            // the author is looking at; items stay the default.
            String type = request.type() == null ? "items" : request.type();
            String path = documentPath(type, request.folder(), request.resourceId());
            String existing = declaredElsewhere(request.pack(), type, path, request.resourceId());
            if (existing != null) {
                return duplicateRefused(request.resourceId(), existing);
            }
            String scaffold = "templates".equals(type) && request.serves() != null
                    ? Scaffolds.templateFor(request.serves(), request.resourceId())
                    : Scaffolds.forPath(path, request.resourceId());
            if (request.template() != null && !request.template().isBlank()) {
                scaffold = templated(request.pack(), request.resourceId(), request.template(), scaffold);
            }
            // Recipes and templates have no display name; forcing one would
            // invent a field the type's schema does not declare.
            String content = scaffold.contains("display_name:")
                    ? LosslessYaml.parse(scaffold)
                            .withScalar(name, request.resourceId(), "display_name").save()
                    : scaffold;
            DocumentStore.Document created = documents.createResource(
                    request.pack(), path, content, request.createPack(), actorId(actor));
            audit.record(actorId(actor), "authoring.createResource", request.pack() + "/" + path);
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), path));
            return ApiResult.ok(view(created));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<DocumentView> duplicateResource(ActorContext actor, DuplicateResourceRequest request) {
        try {
            String type = request.fromPath().contains("/")
                    ? request.fromPath().substring(0, request.fromPath().indexOf('/')) : "";
            String path = documentPath(type, request.folder(), request.resourceId());
            String existing = declaredElsewhere(request.pack(), type, path, request.resourceId());
            if (existing != null) {
                return duplicateRefused(request.resourceId(), existing);
            }
            String content = rekeyedCopy(request.pack(), request.fromPath(), request.resourceId());
            int revision = documents.createDocument(request.pack(), path, content, actorId(actor));
            audit.record(actorId(actor), "authoring.duplicateResource",
                    request.pack() + "/" + path);
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), path));
            return ApiResult.ok(new DocumentView(
                    request.pack(), path, content, parsed(content),
                    revision, Documents.sha256(content), Map.of()));
        } catch (NoSuchFileException e) {
            return missing("no such document");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return new ApiResult<DocumentView>(false, null, List.of(new Diagnostic(
                    DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                    e.getMessage(), null, null, "choose a different id")));
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    /**
     * A new resource that is only a link: {@code template:} (plus the display
     * name when the type has one). The template supplies everything else, so
     * the new file carries nothing that could drift from it.
     */
    private String templated(String pack, String resourceId, String template, String scaffold) {
        if (!template.matches("[a-z0-9_-]{2,64}(:[a-z0-9_-]{2,64})?")) {
            throw new IllegalArgumentException("template must be an id like 'base_item' or 'pack:base_item'");
        }
        String id = sanitizedId(resourceId);
        String full = TemplateResolver.shortRef(namespaceOf(pack), template);
        if (!summaryComputer.templatePool().definitions().containsKey(full)) {
            throw new IllegalArgumentException("unknown template " + full);
        }
        String named = scaffold.contains("display_name:") ? "  display_name: \"" + id + "\"\n" : "";
        return "# " + id + " — created by the Materione editor\n"
                + id + ":\n"
                + "  template: " + template + "\n"
                + named;
    }

    /** Placeholder child of a freshly seeded template document, removed in the same write. */
    private static final String SEED = "__seed";

    private static String sanitizedId(String resourceId) {
        if (resourceId == null || !resourceId.matches("[a-z0-9_-]{2,64}")) {
            throw new IllegalArgumentException("id must be 2..64 chars of a-z 0-9 _ -");
        }
        return resourceId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ApiResult<List<TemplateInfo>> listTemplates() {
        try {
            ResourceSummaryComputer.TemplateCatalog catalog = summaryComputer.templateCatalog();
            List<ContentType> capable = catalog.types().stream()
                    .filter(AuthoringApiImpl::acceptsTemplates).toList();
            List<TemplateInfo> out = new ArrayList<>();
            catalog.pool().definitions().forEach((full, definition) -> {
                List<String> source = catalog.pool().sources().get(full);
                String pack = source.get(0);
                String parentRef = TemplateResolver.text(definition, "template");
                List<ResourceRef> users = catalog.users().getOrDefault(full, List.of()).stream()
                        .map(ref -> new ResourceRef(ref.pack(), ref.type(), ref.path(), ref.resourceId()))
                        .toList();
                out.add(new TemplateInfo(
                        pack, source.get(1), full.substring(full.indexOf(':') + 1),
                        parentRef == null ? null : TemplateResolver.shortRef(namespaceOf(pack), parentRef),
                        (Map<String, Object>) LosslessYaml.stringKeyedDeep(definition),
                        serves(definition, users, capable), users));
            });
            out.sort(java.util.Comparator.comparing(TemplateInfo::pack).thenComparing(TemplateInfo::id));
            return ApiResult.ok(out);
        } catch (RuntimeException e) {
            return handlerFailure(e);
        }
    }

    /** A type takes part in inheritance when one of its fields is a template reference. */
    private static boolean acceptsTemplates(ContentType type) {
        return type.describe().parameters().stream().anyMatch(parameter ->
                parameter.editor() != null && "template".equals(parameter.editor().referenceKind()));
    }

    /**
     * The type directories a template serves, derived and never stored: the
     * template-capable types of the resources that use it, else the type(s)
     * whose fields the template's keys match best, else every capable type.
     */
    private static List<String> serves(
            Map<String, Object> definition, List<ResourceRef> users, List<ContentType> capable) {
        List<String> used = capable.stream().map(ContentType::directory)
                .filter(directory -> users.stream().anyMatch(user -> user.type().equals(directory)))
                .toList();
        if (!used.isEmpty()) {
            return used;
        }
        int best = 0;
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (ContentType type : capable) {
            java.util.Set<String> known = new java.util.HashSet<>();
            type.describe().parameters().forEach(parameter -> known.add(parameter.id()));
            int score = (int) definition.keySet().stream()
                    .filter(key -> !key.equals("template") && known.contains(key)).count();
            scores.put(type.directory(), score);
            best = Math.max(best, score);
        }
        if (best == 0) {
            return capable.stream().map(ContentType::directory).toList();
        }
        final int top = best;
        return scores.entrySet().stream().filter(entry -> entry.getValue() == top)
                .map(Map.Entry::getKey).toList();
    }

    @Override
    public ApiResult<DocumentView> detachTemplate(ActorContext actor, DetachTemplateRequest request) {
        try {
            DocumentStore.Document source = documents.read(request.pack(), request.path());
            var parsedSource = LosslessYaml.parse(source.raw());
            if (parsedSource.parseError().isPresent()) {
                throw new IllegalArgumentException(
                        "cannot detach in a file that does not parse: " + parsedSource.parseError().get());
            }
            String root = request.resourceId() != null && !request.resourceId().isBlank()
                    ? request.resourceId()
                    : String.valueOf(parsedSource.data().keySet().iterator().next());
            if (!(parsedSource.data().get(root) instanceof Map<?, ?> rawDefinition)) {
                throw new IllegalArgumentException("no resource '" + root + "' in " + request.path());
            }
            Map<String, Object> definition = LosslessYaml.stringKeyed(rawDefinition);
            String authored = TemplateResolver.text(definition, "template");
            if (authored == null) {
                throw new IllegalArgumentException(root + " is not based on a template");
            }
            // Only the chain is resolved: what the resource would inherit if it set nothing.
            TemplateResolver.Resolution chain = TemplateResolver.resolve(
                    Map.of("template", authored), namespaceOf(request.pack()),
                    summaryComputer.templatePool().definitions());
            if (chain.merged() == null) {
                throw new IllegalArgumentException("template does not resolve: " + chain.failure());
            }
            List<DocumentStore.FieldEdit> edits = new ArrayList<>();
            for (String key : chain.sources().keySet()) {
                if (!key.equals("template") && !definition.containsKey(key)) {
                    edits.add(new DocumentStore.FieldEdit(
                            List.of(root, key), LosslessYaml.stringKeyedDeep(chain.merged().get(key))));
                }
            }
            edits.add(new DocumentStore.FieldEdit(List.of(root, "template"), null, true));
            DocumentStore.Document updated = documents.setScalars(
                    request.pack(), request.path(), request.expectedRevision(), edits, actorId(actor));
            audit.record(actorId(actor), "authoring.detachTemplate",
                    request.pack() + "/" + request.path() + " (" + (edits.size() - 1) + " values)");
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), request.path()));
            return ApiResult.ok(view(updated));
        } catch (DocumentConflictException e) {
            return conflict(e, "reopen the resource and detach again");
        } catch (NoSuchFileException e) {
            return missing("no such document");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<DocumentView> saveAsTemplate(ActorContext actor, SaveAsTemplateRequest request) {
        try {
            String templateId = sanitizedId(request.templateId());
            List<String> fields = request.fields() == null ? List.of() : request.fields();
            if (fields.isEmpty() || fields.size() > 256) {
                throw new IllegalArgumentException("choose at least one field to put in the template");
            }
            DocumentStore.Document source = documents.read(request.pack(), request.fromPath());
            var parsedSource = LosslessYaml.parse(source.raw());
            if (parsedSource.parseError().isPresent()) {
                throw new IllegalArgumentException(
                        "cannot read a file that does not parse: " + parsedSource.parseError().get());
            }
            String root = request.resourceId() != null && !request.resourceId().isBlank()
                    ? request.resourceId()
                    : String.valueOf(parsedSource.data().keySet().iterator().next());
            if (!(parsedSource.data().get(root) instanceof Map<?, ?> rawDefinition)) {
                throw new IllegalArgumentException("no resource '" + root + "' in " + request.fromPath());
            }
            Map<String, Object> definition = LosslessYaml.stringKeyed(rawDefinition);
            if (summaryComputer.templatePool().definitions()
                    .containsKey(namespaceOf(request.pack()) + ":" + templateId)) {
                throw new IllegalStateException("template already exists: " + templateId);
            }
            List<DocumentStore.FieldEdit> edits = new ArrayList<>();
            for (String field : fields) {
                if (!definition.containsKey(field)) {
                    throw new IllegalArgumentException(root + " does not set '" + field + "'");
                }
                edits.add(new DocumentStore.FieldEdit(
                        List.of(templateId, field), LosslessYaml.stringKeyedDeep(definition.get(field))));
            }
            // the base needs one child so the lossless layer has a block mapping to append to
            edits.add(new DocumentStore.FieldEdit(List.of(templateId, SEED), null, true));
            String path = "templates/" + templateId + ".yml";
            String base = "# " + templateId + " — saved from " + request.fromPath()
                    + " by the Materione editor\n" + templateId + ":\n  " + SEED + ": 0\n";
            DocumentStore.Document created = documents.applyBatch(
                    request.pack(),
                    List.of(new DocumentStore.BatchStep(path, DocumentStore.BatchKind.CREATE, 0, base, edits)),
                    false, actorId(actor)).get(0).after();
            audit.record(actorId(actor), "authoring.saveAsTemplate", request.pack() + "/" + path);
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), path));
            return ApiResult.ok(view(created));
        } catch (NoSuchFileException e) {
            return missing("no such document");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<DocumentView> moveDocument(ActorContext actor, MoveDocumentRequest request) {
        try {
            DocumentStore.Document moved = documents.move(
                    request.pack(), request.fromPath(), request.expectedRevision(),
                    request.toPath(), actorId(actor));
            audit.record(actorId(actor), "edit.move",
                    request.pack() + "/" + request.fromPath() + " -> " + request.toPath());
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), request.fromPath()));
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), request.toPath()));
            return ApiResult.ok(view(moved));
        } catch (DocumentConflictException e) {
            return conflict(e, "re-read the document and retry");
        } catch (NoSuchFileException e) {
            return missing("no such document: " + e.getFile());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<Void> deleteDocument(ActorContext actor, DeleteDocumentRequest request) {
        try {
            documents.delete(request.pack(), request.path(), request.expectedRevision(),
                    actorId(actor));
            audit.record(actorId(actor), "edit.delete", request.pack() + "/" + request.path());
            reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), request.path()));
            return ApiResult.ok(null);
        } catch (DocumentConflictException e) {
            return conflict(e, "re-read the document and retry");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    // ------------------------------------------------------------------
    // model transactions

    @Override
    public ApiResult<SaveModelResult> saveModelTransaction(ActorContext actor, SaveModelRequest request) {
        try {
            List<ModelTransaction.Asset> assets = new ArrayList<>();
            if (request.assets() != null) {
                for (ModelAsset asset : request.assets()) {
                    assets.add(new ModelTransaction.Asset(asset.path(), asset.bytes()));
                }
            }
            ModelTransaction spec = new ModelTransaction(
                    request.pack(), request.ownerPath(), request.createOwner(),
                    request.ownerId(), request.ownerType(), request.ownerName(),
                    request.modelReference(), request.ownerField(),
                    request.expectedRevision(), request.sourcePath(), assets, actorId(actor));
            DocumentStore.Document updated = modelTransactions.save(spec);
            reloadHook.afterChange(actor, request.pack(), packsRoot.resolve(request.pack()));
            if (updated == null) {
                return ApiResult.ok(new SaveModelResult(null, null, null));
            }
            return ApiResult.ok(new SaveModelResult(
                    updated.path(), updated.revision(), updated.raw()));
        } catch (DocumentConflictException e) {
            return conflict(e, "return to the item and reopen its model");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new ApiResult<SaveModelResult>(false, null, List.of(Diagnostic.error(
                    DiagnosticCodes.EDITOR_ASSET_REJECTED, e.getMessage())));
        } catch (Exception e) {
            return new ApiResult<SaveModelResult>(false, null, List.of(new Diagnostic(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                    "model transaction failed: " + e.getMessage(), null, null,
                    "no partial model changes were kept")));
        }
    }

    // ------------------------------------------------------------------
    // validation, graph, change feed

    @Override
    public ApiResult<Void> validateDraft(ValidateDraftRequest request) {
        // v1: syntax-level gate (what write would reject); semantic
        // validation streams back from the pipeline run that follows.
        var parseError = LosslessYaml.parse(request.content()).parseError();
        if (parseError.isPresent()) {
            return new ApiResult<Void>(false, null, List.of(Diagnostic.error(
                    DiagnosticCodes.CONTENT_PARSE_FAILED, parseError.get())));
        }
        return ApiResult.ok(null);
    }

    @Override
    public ApiResult<GraphQueryResult> queryGraph(GraphQuery query) {
        GraphQuery safe = query == null ? GraphQuery.unresolved() : query;
        ContentGraph content = graph.get();
        if (content == null) {
            return ApiResult.ok(new GraphQueryResult(List.of(), List.of()));
        }
        List<ResourceRef> referencing = List.of();
        if (safe.target() != null) {
            List<ResourceRef> found = new ArrayList<>();
            for (ContentGraph.Node node : content.referencing(safe.target())) {
                found.add(refOf(node));
            }
            referencing = found;
        }
        // Contract gap (frozen api/ this phase): GraphQueryResult.UnresolvedReference
        // is a package-private nested record, so no implementation outside
        // dev.materione.api.authoring can construct it. The broken-reference
        // data is available (content.unresolved()) the moment the record is
        // made public; until then the list reports empty.
        return ApiResult.ok(new GraphQueryResult(referencing, List.of()));
    }

    /**
     * The source file's bytes with its root key re-keyed, so comments and
     * unknown fields survive the copy (§5.6).
     */
    private String rekeyedCopy(String pack, String fromPath, String newId) throws java.io.IOException {
        DocumentStore.Document source = documents.read(pack, fromPath);
        var parsedSource = LosslessYaml.parse(source.raw());
        if (parsedSource.parseError().isPresent()) {
            throw new IllegalArgumentException(
                    "cannot copy a file that does not parse: " + parsedSource.parseError().get());
        }
        String sourceId = String.valueOf(parsedSource.data().keySet().iterator().next());
        return source.raw().replaceFirst(
                "(?m)^" + Pattern.quote(sourceId) + ":",
                java.util.regex.Matcher.quoteReplacement(newId + ":"));
    }

    @Override
    public ApiResult<ReferenceResult> findReferences(ReferenceQuery query) {
        try {
            if (query == null || query.kind() == null || query.target() == null || query.target().isBlank()
                    || query.target().length() > 256 || query.target().contains("..")
                    || query.pack() == null || !query.pack().matches("[a-z0-9_-]{2,64}")) {
                throw new IllegalArgumentException("a reference query needs a kind, a pack and a target");
            }
            return ApiResult.ok(new ReferenceScanner(documents).scan(query));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<BulkResult> bulk(ActorContext actor, BulkRequest request) {
        try {
            List<BulkOperation> operations = request.operations();
            if (operations == null || operations.isEmpty()) {
                throw new IllegalArgumentException("'operations' must be a non-empty array");
            }
            if (operations.size() > MAX_BULK_OPERATIONS) {
                throw new IllegalArgumentException(
                        "at most " + MAX_BULK_OPERATIONS + " documents per bulk change");
            }
            List<DocumentStore.BatchStep> steps = new ArrayList<>();
            for (BulkOperation op : operations) {
                if (op.kind() == null) {
                    throw new IllegalArgumentException("every operation needs a kind");
                }
                if (op.edits().size() > 256) {
                    throw new IllegalArgumentException("too many edits for one document");
                }
                List<DocumentStore.FieldEdit> edits = new ArrayList<>();
                for (FieldEdit edit : op.edits()) {
                    edits.add(new DocumentStore.FieldEdit(edit.field(), edit.value(), edit.remove()));
                }
                switch (op.kind()) {
                    case SET -> {
                        if (op.path() == null || edits.isEmpty()) {
                            throw new IllegalArgumentException("a patch needs a path and edits");
                        }
                        steps.add(new DocumentStore.BatchStep(
                                op.path(), DocumentStore.BatchKind.SET, op.expectedRevision(), null, edits));
                    }
                    case CREATE -> {
                        String type = op.type() == null ? "items" : op.type();
                        String path = documentPath(type, op.folder(), op.resourceId());
                        String scaffold = Scaffolds.forPath(path, op.resourceId());
                        String name = op.displayName() == null || op.displayName().isBlank()
                                ? null : displayName(op.displayName());
                        String content = name != null && scaffold.contains("display_name:")
                                ? LosslessYaml.parse(scaffold)
                                        .withScalar(name, op.resourceId(), "display_name").save()
                                : scaffold;
                        steps.add(new DocumentStore.BatchStep(
                                path, DocumentStore.BatchKind.CREATE, 0, content, edits));
                    }
                    case DUPLICATE -> {
                        if (op.fromPath() == null) {
                            throw new IllegalArgumentException("a duplicate needs the path to copy");
                        }
                        String type = op.fromPath().contains("/")
                                ? op.fromPath().substring(0, op.fromPath().indexOf('/')) : "";
                        String path = documentPath(type, op.folder(), op.resourceId());
                        steps.add(new DocumentStore.BatchStep(
                                path, DocumentStore.BatchKind.CREATE, 0,
                                rekeyedCopy(request.pack(), op.fromPath(), op.resourceId()), edits));
                    }
                }
            }
            String actorId = actorId(actor);
            // Plan first: nothing is written until every document validated.
            List<DocumentStore.BatchOutcome> plan =
                    documents.applyBatch(request.pack(), steps, true, actorId);
            List<DocumentStore.BatchStep> effective = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                DocumentStore.BatchOutcome outcome = plan.get(i);
                if (outcome.kind() == DocumentStore.BatchKind.CREATE
                        || !outcome.after().raw().equals(outcome.before())) {
                    effective.add(steps.get(i));
                }
            }
            Map<String, DocumentStore.BatchOutcome> written = new LinkedHashMap<>();
            boolean applied = false;
            if (!request.dryRun() && !effective.isEmpty()) {
                for (DocumentStore.BatchOutcome outcome
                        : documents.applyBatch(request.pack(), effective, false, actorId)) {
                    written.put(outcome.path(), outcome);
                }
                applied = true;
                audit.record(actorId, "authoring.bulk",
                        request.pack() + " (" + written.size() + " documents)");
                for (String path : written.keySet()) {
                    reloadHook.afterChange(actor, request.pack(), documentFile(request.pack(), path));
                }
            }
            List<BulkDocumentResult> results = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                DocumentStore.BatchStep step = steps.get(i);
                DocumentStore.BatchOutcome planned = plan.get(i);
                boolean unchanged = !effective.contains(step);
                DocumentStore.BatchOutcome shown = written.getOrDefault(step.path(), planned);
                results.add(new BulkDocumentResult(
                        step.path(), operations.get(i).kind(), shown.after().revision(), unchanged,
                        unchanged ? List.of() : changes(planned, operations.get(i).edits())));
            }
            return ApiResult.ok(new BulkResult(applied, results));
        } catch (DocumentConflictException e) {
            return conflict(e, "reload the affected documents and review the change again");
        } catch (NoSuchFileException e) {
            return missing("no such document: " + e.getFile());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    private static List<BulkFieldChange> changes(DocumentStore.BatchOutcome outcome, List<FieldEdit> edits) {
        Map<String, Object> before = outcome.before() == null ? null : parsed(outcome.before());
        Map<String, Object> after = parsed(outcome.after().raw());
        List<BulkFieldChange> out = new ArrayList<>();
        for (FieldEdit edit : edits) {
            Object was = valueAt(before, edit.field());
            Object now = valueAt(after, edit.field());
            if (!Objects.equals(was, now)) {
                out.add(new BulkFieldChange(edit.field(), was, now));
            }
        }
        return out;
    }

    private static Object valueAt(Map<String, Object> tree, List<String> field) {
        Object node = tree;
        for (String key : field) {
            if (node instanceof Map<?, ?> map) {
                node = map.get(key);
            } else {
                return null;
            }
        }
        return node;
    }

    @Override
    public AutoCloseable subscribeChanges(Consumer<DocumentChangeEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    /** Invalidates the summary projection when a watched directory is created or moved in. */
    public void invalidateResourceSummaries() {
        summaryComputer.invalidate();
    }

    /**
     * Fires the change feed — the file watcher and API saves alike (the
     * editor's {@code push.document.changed}). Fail-soft per listener.
     */
    public void publishChange(String pack, String path, boolean deleted) {
        invalidateResourceSummaries();
        DocumentChangeEvent event = new DocumentChangeEvent(pack, path, deleted);
        for (Consumer<DocumentChangeEvent> listener : changeListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                // one misbehaving subscriber never blocks the feed (D6)
            }
        }
    }

    // ------------------------------------------------------------------

    /** The store's document as the API sees it, with computed metadata attached. */
    private DocumentView view(DocumentStore.Document document) {
        Map<String, Object> tree = parsed(document.raw());
        Map<String, Object> computed = Map.of();
        if (computedMeta != null && tree != null) {
            Map<String, Object> metadata = computedMeta.compute(document.pack(), document.path(), tree);
            if (metadata != null) {
                computed = metadata;
            }
        }
        Map<String, Object> inheritance = tree == null ? Map.of() : inheritance(document.pack(), tree);
        if (!inheritance.isEmpty()) {
            Map<String, Object> merged = new LinkedHashMap<>(computed);
            merged.put("inheritance", inheritance);
            computed = Collections.unmodifiableMap(merged);
        }
        return new DocumentView(
                document.pack(), document.path(), document.raw(), tree,
                document.revision(), document.sha256(), computed);
    }

    /**
     * Effective values and provenance for every root that names a
     * {@code template:} (web.md §6), from the same resolver the pipeline
     * compiles with ({@link TemplateResolver#resolve}). Read-only: the canonical
     * file never contains any of it. Per root: {@code template} (as
     * authored), {@code chain} (full ids, nearest first), {@code inherited}
     * (the value the chain supplies for each key — what the key resolves to
     * whenever the root does not set it), {@code sources} (which template
     * supplies each of those keys),
     * {@code templates} ({@code {pack, path}} per chain id) and {@code failure}
     * when the chain does not resolve. Template documents are only read for
     * documents that use inheritance.
     */
    private Map<String, Object> inheritance(String pack, Map<String, Object> tree) {
        Map<String, Object> out = new LinkedHashMap<>();
        ResourceSummaryComputer.TemplatePool pool = null;
        for (Map.Entry<String, Object> root : tree.entrySet()) {
            if (!(root.getValue() instanceof Map<?, ?> rawDefinition)) {
                continue;
            }
            Map<String, Object> definition = new LinkedHashMap<>();
            rawDefinition.forEach((key, value) -> definition.put(String.valueOf(key), value));
            String authored = TemplateResolver.text(definition, "template");
            if (authored == null) {
                continue;
            }
            if (pool == null) {
                try {
                    pool = summaryComputer.templatePool();
                } catch (RuntimeException e) {
                    return Map.of(); // over budget or unreadable: report nothing rather than a guess
                }
            }
            String namespace = namespaceOf(pack);
            TemplateResolver.Resolution resolution = TemplateResolver.resolve(definition, namespace, pool.definitions());
            TemplateResolver.Resolution chainOnly = resolution.merged() == null
                    ? resolution : TemplateResolver.resolve(Map.of("template", authored), namespace, pool.definitions());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("template", authored);
            view.put("chain", resolution.chain());
            Map<String, Object> inherited = new LinkedHashMap<>();
            Map<String, Object> sources = new LinkedHashMap<>();
            if (chainOnly.merged() != null) {
                chainOnly.sources().forEach((key, source) -> {
                    if (!key.equals("template")) {
                        inherited.put(key, chainOnly.merged().get(key));
                        sources.put(key, source);
                    }
                });
            }
            view.put("inherited", inherited);
            view.put("sources", sources);
            Map<String, Object> locations = new LinkedHashMap<>();
            for (String id : resolution.chain()) {
                List<String> source = pool.sources().get(id);
                if (source != null) {
                    locations.put(id, Map.of("pack", source.get(0), "path", source.get(1)));
                }
            }
            view.put("templates", locations);
            if (resolution.failure() != null) {
                view.put("failure", resolution.failure());
            }
            out.put(root.getKey(), view);
        }
        return out;
    }

    /** The namespace short template references resolve against: the pack's own id. */
    private static String namespaceOf(String pack) {
        return pack;
    }

    /**
     * The parsed tree as plain string-keyed maps, or null when the YAML does
     * not parse. YAML-1.1 boolean keys (a bare ECA {@code on:}) are
     * normalized at every depth so the editor sees the key the author wrote.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsed(String raw) {
        try {
            var document = LosslessYaml.parse(raw);
            return document.parseError().isPresent()
                    ? null : (Map<String, Object>) LosslessYaml.stringKeyedDeep(document.data());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Path documentFile(String pack, String path) {
        return packsRoot.resolve(pack).resolve(path);
    }

    /** Graph node → resource ref, deriving pack/type/path from the source file. */
    private ResourceRef refOf(ContentGraph.Node node) {
        String pack = node.id().namespace();
        String path = node.contentType() + "/" + node.id().value();
        if (node.sourceFile() != null && node.sourceFile().startsWith(packsRoot)) {
            Path relative = packsRoot.relativize(node.sourceFile());
            if (relative.getNameCount() > 1) {
                pack = relative.getName(0).toString();
                path = joinTail(relative);
            }
        }
        String type = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        return new ResourceRef(pack, type, path, node.id().value());
    }

    private static String joinTail(Path relative) {
        StringBuilder out = new StringBuilder();
        for (Path segment : relative.subpath(1, relative.getNameCount())) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(segment);
        }
        return out.toString();
    }

    /**
     * Builds the pack-relative path for a new document from the type tab and
     * the folder the author is browsing. Both are validated here so a hostile
     * caller cannot smuggle a path outside the type's directory.
     */
    private static String documentPath(String type, String folder, String id) {
        if (!Scaffolds.SCAFFOLD_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "unknown content type '" + type + "' — expected one of "
                            + Scaffolds.SCAFFOLD_TYPES);
        }
        if (id == null || !id.matches("[a-z0-9_-]{2,64}")) {
            throw new IllegalArgumentException("id must be 2..64 chars of a-z 0-9 _ -");
        }
        String prefix = type;
        if (folder != null && !folder.isBlank()) {
            String cleaned = folder.replaceAll("^/+|/+$", "");
            if (cleaned.startsWith(type + "/")) {
                cleaned = cleaned.substring(type.length() + 1);
            } else if (cleaned.equals(type)) {
                cleaned = "";
            }
            if (!cleaned.isEmpty()) {
                if (!cleaned.matches("[A-Za-z0-9_ -]+(/[A-Za-z0-9_ -]+)*")) {
                    throw new IllegalArgumentException("bad folder: " + folder);
                }
                prefix = type + "/" + cleaned;
            }
        }
        return prefix + "/" + id + ".yml";
    }

    /** Display names are trimmed and length-checked exactly like the editor op. */
    private static String displayName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 128) {
            throw new IllegalArgumentException("display name must be 1..128 characters");
        }
        return name;
    }

    /** The root mapping key of a freshly scaffolded document. */
    private static String rootKey(String content) {
        return String.valueOf(LosslessYaml.parse(content).data().keySet().iterator().next());
    }

    private static String actorId(ActorContext actor) {
        return Objects.requireNonNull(actor, "actor").actorId();
    }

    // ------------------------------------------------------------------
    // diagnostics (same codes and hints the wire always carried)

    private static <T> ApiResult<T> conflict(DocumentConflictException e, String hint) {
        return new ApiResult<T>(false, null, List.of(new Diagnostic(
                DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT, Severity.WARN,
                e.getMessage(), null, null, hint)));
    }

    /**
     * The pack-relative path of a document that already declares {@code id} for this content
     * directory, or null. It scans the documents the pipeline scans ({@link PackLayout}), so a
     * refusal here and an MTR-0056 at parse time can never disagree about what is a duplicate.
     */
    private String declaredElsewhere(String pack, String contentDir, String path, String id) {
        Path packDir = packsRoot.resolve(pack);
        if (id == null || !Files.isDirectory(packDir)) {
            return null;
        }
        if (Files.exists(documentFile(pack, path))) {
            // the same file is the clash: the store refuses it as an existing document (MTR-0008)
            return null;
        }
        for (Path file : PackLayout.contentDocuments(packDir, contentDir, failure -> { })) {
            try {
                var document = LosslessYaml.parse(Files.readString(file));
                if (document.parseError().isEmpty() && document.data().get(id) instanceof Map<?, ?>) {
                    return packDir.relativize(file).toString().replace('\\', '/');
                }
            } catch (IOException e) {
                // unreadable: the pipeline reports it; not a duplicate this check can prove
            }
        }
        return null;
    }

    private static <T> ApiResult<T> duplicateRefused(String id, String existing) {
        return new ApiResult<T>(false, null, List.of(new Diagnostic(
                DiagnosticCodes.DUPLICATE_RESOURCE_ID, Severity.ERROR,
                "resource " + id + " is already declared in " + existing,
                null, null, "open the existing resource, or choose a different id")));
    }

    private static <T> ApiResult<T> badRequest(String message) {
        return new ApiResult<T>(false, null, List.of(Diagnostic.error(
                DiagnosticCodes.CONTENT_SCHEMA_INVALID, message)));
    }

    private static <T> ApiResult<T> missing(String message) {
        return new ApiResult<T>(false, null, List.of(Diagnostic.error(
                DiagnosticCodes.UNRESOLVED_REFERENCE, message)));
    }

    private static <T> ApiResult<T> handlerFailure(Exception e) {
        return new ApiResult<T>(false, null, List.of(Diagnostic.error(
                DiagnosticCodes.PIPELINE_STAGE_FAILED, e.getMessage() != null ? e.getMessage() : e.toString())));
    }
}
