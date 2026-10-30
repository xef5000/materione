package dev.materione.content.gui;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.BulkDocumentResult;
import dev.materione.api.authoring.AuthoringApi.BulkKind;
import dev.materione.api.authoring.AuthoringApi.BulkOperation;
import dev.materione.api.authoring.AuthoringApi.BulkRequest;
import dev.materione.api.authoring.AuthoringApi.BulkResult;
import dev.materione.api.authoring.AuthoringApi.DocumentView;
import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.api.authoring.AuthoringApi.ResourceFilter;
import dev.materione.api.authoring.AuthoringApi.ResourceRef;
import dev.materione.api.authoring.AuthoringApi.SetFieldsRequest;
import dev.materione.api.authoring.AuthoringApi.TemplateInfo;
import dev.materione.api.content.Allocations;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.api.gui.GuiAuthoringApi.GuiEdit;
import dev.materione.api.gui.GuiAuthoringApi.OpenSpec;
import dev.materione.api.gui.GuiAuthoringApi.SetOpen;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.TextureSource;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.LosslessYamlDocument;
import dev.materione.core.pipeline.ResourceCompiler;
import dev.materione.core.texture.PackTextures;
import dev.materione.core.texture.PngDecoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The GUI builder's typed surface (gui.md §7, §10) over the authoring API. This is the one
 * implementation the studio, the editor RPC and any addon share: layer edits are
 * {@code setFields} transactions, customization is one {@code bulk}, and {@link #validate} and
 * {@link #render} run the same parse, diagnose and compile steps the pack compiler runs, with
 * a non-persisting allocation view (so a preview leaves the allocation table untouched).
 *
 * <p>Writes never bypass {@link AuthoringApi}: there is no second write path (D6). The compile
 * steps are the pack compiler's own ({@link ResourceCompiler}, in core), so there is one sequence, not a copy.
 */
public final class GuiAuthoringApiImpl implements GuiAuthoringApi {

    private static final String TEXTURE_PREFIX = "assets/textures/";
    private static final String ITEM_FOLDER_PREFIX = "items/";
    private static final String MINECRAFT = "minecraft:";

    /**
     * The logic compiler's verdict on a GUI's {@code behavior:} and {@code flows:} (logic.md §10).
     * The content module does not depend on the behavior engine, so the host supplies it; null means
     * no logic diagnostics.
     */
    @FunctionalInterface
    public interface LogicCheck {
        List<Diagnostic> check(NamespacedId owner, Map<String, Object> definition);
    }

    private final AuthoringApi authoring;
    private final Registry<GuiComponent> components;
    private final GuiType type;
    private final Path packsRoot;
    private final Supplier<Allocations> previewAllocations;
    private final List<String> materials;
    private final LogicCheck logic;

    /**
     * @param authoring the authoring API the writes and the reads go through
     * @param components the {@code gui-components} registry layers resolve against
     * @param packsRoot the directory of the packs ({@code content/packs})
     * @param previewAllocations a non-persisting allocation view (the pack's table's preview)
     * @param materials the vanilla materials a customized item may name; empty = unchecked
     */
    public GuiAuthoringApiImpl(
            AuthoringApi authoring,
            Registry<GuiComponent> components,
            Path packsRoot,
            Supplier<Allocations> previewAllocations,
            List<String> materials) {
        this(authoring, components, packsRoot, previewAllocations, materials, null);
    }

    /**
     * @param logic the logic compiler's check, folded into {@link #validate} and the warnings of
     *              {@link #edit}; null when the host has no behavior engine
     */
    public GuiAuthoringApiImpl(
            AuthoringApi authoring,
            Registry<GuiComponent> components,
            Path packsRoot,
            Supplier<Allocations> previewAllocations,
            List<String> materials,
            LogicCheck logic) {
        this.authoring = Objects.requireNonNull(authoring, "authoring");
        this.components = Objects.requireNonNull(components, "components");
        this.packsRoot = Objects.requireNonNull(packsRoot, "packsRoot");
        this.previewAllocations = Objects.requireNonNull(previewAllocations, "previewAllocations");
        this.materials = materials == null ? List.of() : List.copyOf(materials);
        this.type = new GuiType(components);
        this.logic = logic;
    }

    // ------------------------------------------------------------------
    // components

    @Override
    public List<GuiComponentDescriptor> components() {
        var out = new ArrayList<GuiComponentDescriptor>();
        for (RegistryEntry<GuiComponent> entry : components.entries()) {
            RenderTarget target;
            try {
                target = entry.entry().target();
            } catch (RuntimeException broken) {
                continue; // an addon whose target throws never places a layer; the compile reports it
            }
            if (target != null) {
                out.add(new GuiComponentDescriptor(entry.id(), target, entry.description(), entry.registeredBy()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // reads

    @Override
    public ApiResult<GuiDocumentView> get(String pack, String path) {
        ApiResult<Subject> loaded = loadSaved(pack, path);
        if (!loaded.ok()) {
            return ApiResult.fail(loaded.diagnostics());
        }
        return ApiResult.ok(view(loaded.value()));
    }

    @Override
    public ApiResult<GuiRender> render(GuiRenderRequest request) {
        ApiResult<Subject> resolved = resolve(request.source());
        if (!resolved.ok()) {
            return ApiResult.fail(resolved.diagnostics());
        }
        Subject subject = resolved.value();
        ApiResult<TextureSource> textures = draftTextures(subject.pack(), request.assets());
        if (!textures.ok()) {
            return ApiResult.fail(textures.diagnostics());
        }
        ApiResult<Map<String, Map<String, Object>>> pool = templatePool(subject);
        if (!pool.ok()) {
            return ApiResult.fail(pool.diagnostics());
        }
        Check check = check(subject, textures.value(), pool.value());
        if (check.gui() == null || check.background() == null) {
            return ApiResult.fail(check.diagnostics());
        }
        GuiDefinition gui = check.gui();
        // the same compile the pack runs, for the per-layer outcome: the codepoint comes from the
        // preview view, so this allocates nothing and the background bytes are the check's
        int codepoint = GuiBackgrounds.codepoint(
                previewAllocations.get().range(gui.id(), GuiBackgrounds.ALLOCATION_KIND, 1).start());
        GuiCompilation compiled = GuiCompiler.compile(gui, codepoint, textures.value());
        return ApiResult.ok(new GuiRender(
                CompiledGui.WIDTH, CompiledGui.height(gui.rows()), check.background(),
                layerRenders(gui, compiled.layers()), check.diagnostics()));
    }

    @Override
    public ApiResult<List<Diagnostic>> validate(GuiValidateRequest request) {
        ApiResult<Subject> resolved = resolve(request.source());
        if (!resolved.ok()) {
            return ApiResult.fail(resolved.diagnostics());
        }
        Subject subject = resolved.value();
        ApiResult<TextureSource> textures = draftTextures(subject.pack(), request.assets());
        if (!textures.ok()) {
            return ApiResult.fail(textures.diagnostics());
        }
        ApiResult<Map<String, Map<String, Object>>> pool = templatePool(subject);
        if (!pool.ok()) {
            return ApiResult.fail(pool.diagnostics());
        }
        return ApiResult.ok(withLogicAndOpen(subject, check(subject, textures.value(), pool.value()).diagnostics()));
    }

    /** The compile's diagnostics, then the open: block's shape problems and the logic compiler's, for one GUI. */
    private List<Diagnostic> withLogicAndOpen(Subject subject, List<Diagnostic> compiled) {
        var all = new ArrayList<Diagnostic>(compiled);
        if (subject.definition().containsKey("open")) {
            GuiOpen.Parsed parsed = GuiOpen.read(subject.definition().get("open"));
            all.addAll(parsed.problems());
            if (parsed.spec() != null) {
                all.addAll(GuiOpen.check(parsed.spec()));
            }
        }
        if (logic != null) {
            all.addAll(logic.check(subject.id(), subject.definition()));
        }
        return all;
    }

    // ------------------------------------------------------------------
    // writes

    @Override
    public ApiResult<GuiDocumentView> edit(ActorContext actor, GuiEditRequest request) {
        ApiResult<Subject> loaded = loadSaved(request.pack(), request.path());
        if (!loaded.ok()) {
            return ApiResult.fail(loaded.diagnostics());
        }
        Subject subject = loaded.value();
        if (subject.revision() != request.expectedRevision()) {
            return conflict(subject.path(), subject.revision(), request.expectedRevision());
        }
        ApiResult<List<Map<String, Object>>> layers = mappingLayers(subject);
        if (!layers.ok()) {
            return ApiResult.fail(layers.diagnostics());
        }
        String title = textOf(subject.definition().get("title"));
        Integer rows = wholeNumber(subject.definition().get("rows"));
        if (rows == null) {
            return badRequest("the GUI's rows are not a whole number: fix the file before editing it");
        }
        GuiLayerEdits.Result next;
        try {
            next = new GuiLayerEdits(this::knownComponent).apply(title, rows, layers.value(), request.edits());
        } catch (IllegalArgumentException refused) {
            return badRequest(refused.getMessage());
        }
        Object openBefore = subject.definition().get("open");
        Object openAfter = openBefore;
        for (GuiEdit edit : request.edits()) {
            if (edit instanceof SetOpen set) {
                if (set.open() == null) {
                    openAfter = null;
                } else {
                    List<Diagnostic> problems = GuiOpen.check(set.open());
                    if (!problems.isEmpty()) {
                        return badRequest(problems.get(0).message());
                    }
                    openAfter = GuiOpen.write(set.open());
                }
            }
        }

        String key = subject.id().value();
        var fields = new ArrayList<FieldEdit>();
        if (!Objects.equals(title, next.title())) {
            fields.add(new FieldEdit(List.of(key, "title"), next.title()));
        }
        if (rows != next.rows()) {
            fields.add(new FieldEdit(List.of(key, "rows"), next.rows()));
        }
        List<Object> authoredLayers = currentLayers(subject);
        if (!authoredLayers.equals(next.layers())) {
            fields.add(new FieldEdit(List.of(key, "layers"), List.copyOf(next.layers())));
        }
        if (!Objects.equals(openBefore, openAfter)) {
            fields.add(openAfter == null
                    ? FieldEdit.removal(List.of(key, "open"))
                    : new FieldEdit(List.of(key, "open"), openAfter));
        }
        if (fields.isEmpty() && request.assets().isEmpty()) {
            return ApiResult.ok(view(subject)); // nothing to change: no revision, no write
        }
        DocumentView written;
        try {
            ApiResult<DocumentView> saved = authoring.setFields(actor, new SetFieldsRequest(
                    request.pack(), request.path(), request.expectedRevision(), fields, request.assets()));
            if (!saved.ok()) {
                return ApiResult.fail(saved.diagnostics());
            }
            written = saved.value();
        } catch (RuntimeException failure) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    failure.getMessage() != null ? failure.getMessage() : failure.toString()));
        }
        ApiResult<Subject> after = parseSaved(request.pack(), request.path(), written);
        if (!after.ok()) {
            return ApiResult.fail(after.diagnostics());
        }
        ApiResult<Map<String, Map<String, Object>>> pool = templatePool(after.value());
        List<Diagnostic> compiled = pool.ok()
                ? check(after.value(), new PackTextures(request.pack(), packsRoot.resolve(request.pack())), pool.value())
                        .diagnostics()
                : List.of();
        return ApiResult.ok(view(after.value()), withLogicAndOpen(after.value(), compiled));
    }

    @Override
    public ApiResult<CustomizeItemResult> customizeItem(ActorContext actor, CustomizeItemRequest request) {
        ApiResult<Subject> loaded = loadSaved(request.pack(), request.path());
        if (!loaded.ok()) {
            return ApiResult.fail(loaded.diagnostics());
        }
        Subject subject = loaded.value();
        if (subject.revision() != request.expectedRevision()) {
            return conflict(subject.path(), subject.revision(), request.expectedRevision());
        }
        ApiResult<List<Map<String, Object>>> layers = mappingLayers(subject);
        if (!layers.ok()) {
            return ApiResult.fail(layers.diagnostics());
        }
        Map<String, Object> layer = layers.value().stream()
                .filter(candidate -> request.layerId().equals(candidate.get("id")))
                .findFirst().orElse(null);
        if (layer == null) {
            return badRequest("no layer '" + request.layerId() + "' in this GUI");
        }
        if (!isItemType(layer.get("type"))) {
            return badRequest("layer '" + request.layerId() + "' is not an item layer");
        }
        String reference = textOf(layer.get("item"));
        if (reference == null || reference.isBlank()) {
            return badRequest("Choose an item first: the layer has none");
        }
        reference = reference.trim();

        ApiResult<List<ResourceRef>> resources = authoring.listResources(new ResourceFilter(request.pack(), null, null));
        if (!resources.ok()) {
            return ApiResult.fail(resources.diagnostics());
        }
        var taken = new HashSet<String>();
        resources.value().forEach(resource -> taken.add(resource.resourceId()));
        String guiPath = request.path();
        String id;
        try {
            id = GuiItemNaming.chooseItemId(request.layerId(), guiPath, taken);
        } catch (IllegalArgumentException none) {
            return badRequest(none.getMessage());
        }
        String folder = GuiItemNaming.customFolder(guiPath);
        String itemPath = GuiItemNaming.customPath(guiPath, id);
        String pointer = request.pack() + ":" + id;

        BulkOperation item;
        if (reference.startsWith(MINECRAFT)) {
            String material = reference.substring(MINECRAFT.length()).toUpperCase(Locale.ROOT);
            if (!materials.isEmpty() && !materials.contains(material)) {
                return badRequest("\"" + reference + "\" is not a material this server knows.");
            }
            item = new BulkOperation(BulkKind.CREATE, null, 0, "items", folder, id, null, null,
                    List.of(new FieldEdit(List.of(id, "material"), material)));
        } else {
            ResourceRef source = itemResource(reference, request.pack(), resources.value());
            if (source == null) {
                return badRequest("\"" + reference + "\" is not an item in this pack, so there is nothing to copy.");
            }
            if (!"items".equals(source.type())) {
                return badRequest("Only items and vanilla materials can be customized (blocks and furniture "
                        + "have no item document to copy).");
            }
            if (source.path().startsWith(ITEM_FOLDER_PREFIX + folder + "/")) {
                return badRequest("This layer already has its own item.");
            }
            // items cannot be templates yet (gui.md §10 #1): the copy starts identical, then it is the layer's
            item = new BulkOperation(BulkKind.DUPLICATE, null, 0, null, folder, id, null, source.path(), List.of());
        }

        List<Object> repointed = new ArrayList<>();
        for (Map<String, Object> entry : layers.value()) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            if (request.layerId().equals(entry.get("id"))) {
                copy.put("item", pointer);
            }
            repointed.add(copy);
        }
        var pointerEdit = new BulkOperation(BulkKind.SET, guiPath, request.expectedRevision(), null, null, null, null,
                null, List.of(new FieldEdit(List.of(subject.id().value(), "layers"), repointed)));
        ApiResult<BulkResult> applied = authoring.bulk(actor,
                new BulkRequest(request.pack(), List.of(item, pointerEdit), false));
        if (!applied.ok()) {
            return ApiResult.fail(applied.diagnostics());
        }
        int revision = applied.value().documents().stream()
                .filter(document -> document.path().equals(guiPath))
                .map(BulkDocumentResult::revision)
                .findFirst()
                .orElse(subject.revision() + 1);
        return ApiResult.ok(new CustomizeItemResult(id, itemPath, pointer, revision));
    }

    // ------------------------------------------------------------------
    // the GUI a request names

    /** A GUI as a request names it: its pack, its id, its authored fields and the file's revision (null for drafts). */
    private record Subject(String pack, String path, NamespacedId id, Map<String, Object> definition, Integer revision) {}

    /** One compile of a subject: the parsed GUI (null when it did not parse), its diagnostics and its background PNG (null when not compiled). */
    private record Check(GuiDefinition gui, List<Diagnostic> diagnostics, byte[] background) {}

    private ApiResult<Subject> loadSaved(String pack, String path) {
        if (path == null || !path.startsWith("guis/")) {
            return badRequest("'" + path + "' is not a GUI document (GUI files live under guis/)");
        }
        ApiResult<DocumentView> document = authoring.getDocument(pack, path);
        if (!document.ok()) {
            return ApiResult.fail(document.diagnostics());
        }
        return parseSaved(pack, path, document.value());
    }

    private ApiResult<Subject> parseSaved(String pack, String path, DocumentView document) {
        if (document.parsed() == null) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.CONTENT_PARSE_FAILED,
                    "document " + path + " does not parse as YAML"));
        }
        return singleGui(document.parsed(), pack, path, document.revision());
    }

    private ApiResult<Subject> resolve(GuiSource source) {
        return switch (source) {
            case Saved saved -> loadSaved(saved.pack(), saved.path());
            case Yaml yaml -> parseYaml(yaml);
            case Typed typed -> typedSubject(typed.document());
        };
    }

    private static ApiResult<Subject> parseYaml(Yaml yaml) {
        LosslessYamlDocument document;
        try {
            document = LosslessYaml.parse(yaml.text() == null ? "" : yaml.text());
        } catch (RuntimeException unreadable) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.CONTENT_PARSE_FAILED, unreadable.getMessage()));
        }
        if (document.parseError().isPresent()) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.CONTENT_PARSE_FAILED,
                    "the draft does not parse as YAML: " + document.parseError().get()));
        }
        return singleGui(stringKeyed(document.data()), yaml.pack(), null, null);
    }

    private static ApiResult<Subject> typedSubject(GuiDocumentView view) {
        NamespacedId id;
        try {
            id = NamespacedId.of(view.pack(), view.id());
        } catch (IllegalArgumentException malformed) {
            return badRequest("the GUI id '" + view.id() + "' is not a valid id");
        }
        return ApiResult.ok(new Subject(view.pack(), view.path(), id, typedDefinition(view), null));
    }

    /** The one GUI resource of a file: a file holds one GUI (gui.md §10 #1, the API edits one per file). */
    private static ApiResult<Subject> singleGui(Map<String, Object> tree, String pack, String path, Integer revision) {
        String key = null;
        Map<String, Object> definition = null;
        int count = 0;
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> mapping) {
                count++;
                key = entry.getKey();
                definition = stringKeyed(mapping);
            }
        }
        if (count == 0) {
            return badRequest("the document holds no GUI resource");
        }
        if (count > 1) {
            return badRequest("the file holds " + count + " GUI resources; the GUI API works on one GUI per file");
        }
        NamespacedId id;
        try {
            id = NamespacedId.of(pack, key);
        } catch (IllegalArgumentException malformed) {
            return badRequest("the GUI id '" + key + "' is not a valid id");
        }
        return ApiResult.ok(new Subject(pack, path, id, definition, revision));
    }

    private static ApiResult<List<Map<String, Object>>> mappingLayers(Subject subject) {
        var out = new ArrayList<Map<String, Object>>();
        Object raw = subject.definition().get("layers");
        if (raw == null) {
            return ApiResult.ok(out);
        }
        if (!(raw instanceof List<?> entries)) {
            return badRequest("the GUI's layers must be a list");
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> mapping)) {
                return badRequest("a layer is not a mapping of its fields: fix the file before editing it");
            }
            out.add(stringKeyed(mapping));
        }
        return ApiResult.ok(out);
    }

    /** The layers exactly as authored, for the no-change comparison. */
    private static List<Object> currentLayers(Subject subject) {
        Object raw = subject.definition().get("layers");
        return raw instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static GuiDocumentView view(Subject subject) {
        var layers = new ArrayList<Layer>();
        if (subject.definition().get("layers") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> mapping) {
                    layers.add(layerView(stringKeyed(mapping)));
                }
            }
        }
        OpenSpec open = subject.definition().containsKey("open")
                ? GuiOpen.read(subject.definition().get("open")).spec() : null;
        return new GuiDocumentView(
                subject.pack(), subject.path(), subject.id().value(),
                textOf(subject.definition().get("title")),
                wholeNumber(subject.definition().get("rows")),
                subject.revision() == null ? -1 : subject.revision(),
                layers, open);
    }

    private static Layer layerView(Map<String, Object> layer) {
        var parameters = new LinkedHashMap<String, Object>();
        layer.forEach((key, value) -> {
            if (!COMMON_KEYS.contains(key)) {
                parameters.put(key, value);
            }
        });
        return new Layer(
                String.valueOf(layer.get("id")),
                String.valueOf(layer.get("type")),
                textOf(layer.get("name")),
                !Boolean.FALSE.equals(layer.get("visible")),
                Boolean.TRUE.equals(layer.get("locked")),
                wholeNumber(layer.get("opacity")),
                parameters);
    }

    /** The fields the gui type owns, not a component (gui.md §2). */
    private static final Set<String> COMMON_KEYS = Set.of("id", "type", "name", "visible", "locked", "opacity");

    /** A typed draft as the authored mapping of its GUI (the pipeline's own shape). */
    private static Map<String, Object> typedDefinition(GuiDocumentView view) {
        var definition = new LinkedHashMap<String, Object>();
        if (view.title() != null) {
            definition.put("title", view.title());
        }
        if (view.rows() != null) {
            definition.put("rows", view.rows());
        }
        var layers = new ArrayList<Object>();
        for (Layer layer : view.layers()) {
            var authored = new LinkedHashMap<String, Object>();
            authored.put("id", layer.id());
            authored.put("type", layer.type());
            layer.parameters().forEach((key, value) -> {
                if (!key.equals("id") && !key.equals("type")) {
                    authored.put(key, value);
                }
            });
            if (layer.name() != null) {
                authored.put("name", layer.name());
            }
            if (!layer.visible()) {
                authored.put("visible", false);
            }
            if (layer.locked()) {
                authored.put("locked", true);
            }
            if (layer.opacity() != null) {
                authored.put("opacity", layer.opacity());
            }
            layers.add(authored);
        }
        definition.put("layers", layers);
        if (view.open() != null) {
            definition.put("open", GuiOpen.write(view.open()));
        }
        return definition;
    }

    // ------------------------------------------------------------------
    // compile (the pack compiler's per-resource steps, for drafts and saves)

    /**
     * One GUI resource through the pack compiler's own per-resource sequence
     * ({@link ResourceCompiler}: parse, diagnose, compile), with a collecting emitter and the
     * non-persisting allocation view. The diagnostics are the ones the pack would publish for the
     * resource, in the order it raises them. A preview quarantines nothing: the diagnostic is the
     * answer, and nothing is skipped on disk.
     */
    private Check check(Subject subject, TextureSource textures, Map<String, Map<String, Object>> templates) {
        NamespacedId id = subject.id();
        var diagnostics = new ArrayList<Diagnostic>();
        var emitter = new CollectingEmitter(textures, diagnostics);
        Optional<Object> parsed = ResourceCompiler.compile(id, subject.definition(), templates, type, emitter,
                previewAllocations.get(), new ResourceCompiler.Sink() {
                    @Override
                    public void publish(Diagnostic diagnostic) {
                        diagnostics.add(diagnostic);
                    }

                    @Override
                    public void quarantine(NamespacedId resource, Diagnostic diagnostic) {
                        // a preview skips nothing on disk; the diagnostic it published is the report
                    }
                });
        if (parsed.isEmpty()) {
            return new Check(null, diagnostics, null);
        }
        byte[] background = emitter.assets.get(GuiFonts.backgroundAsset(id.namespace(), id.value()));
        return new Check((GuiDefinition) parsed.get(), diagnostics, background);
    }

    /** Every template of every pack, by {@code pack:id}, when the GUI inherits from one; empty otherwise. */
    private ApiResult<Map<String, Map<String, Object>>> templatePool(Subject subject) {
        if (!subject.definition().containsKey("template")) {
            return ApiResult.ok(Map.of());
        }
        ApiResult<List<TemplateInfo>> listed = authoring.listTemplates();
        if (!listed.ok()) {
            return ApiResult.fail(listed.diagnostics());
        }
        var pool = new HashMap<String, Map<String, Object>>();
        for (TemplateInfo template : listed.value()) {
            pool.put(template.pack() + ":" + template.id(), template.definition());
        }
        return ApiResult.ok(pool);
    }

    /** The saved pack's pixels, with a draft's PNGs laid over them (paths under {@code assets/textures/}). */
    private ApiResult<TextureSource> draftTextures(String pack, List<AuthoringApi.ModelAsset> assets) {
        TextureSource saved = new PackTextures(pack, packsRoot.resolve(pack));
        if (assets == null || assets.isEmpty()) {
            return ApiResult.ok(saved);
        }
        var drafts = new HashMap<String, TextureSource.Pixels>();
        for (AuthoringApi.ModelAsset asset : assets) {
            String path = asset.path();
            if (path == null || !path.startsWith(TEXTURE_PREFIX) || !path.endsWith(".png")) {
                return badRequest("a draft asset must be a PNG under " + TEXTURE_PREFIX + ": " + path);
            }
            String reference = path.substring(TEXTURE_PREFIX.length(), path.length() - ".png".length());
            Optional<TextureSource.Pixels> decoded = PngDecoder.decode(asset.bytes());
            if (decoded.isEmpty()) {
                return badRequest("the draft asset " + path + " is not a readable PNG");
            }
            drafts.put(reference, decoded.get());
        }
        return ApiResult.ok(new DraftTextures(saved, pack, drafts));
    }

    /** The layers in authored order: the compiler's per-layer outcome, with parse skips in their place. */
    private static List<LayerRender> layerRenders(GuiDefinition gui,
            List<GuiCompilation.LayerReport> placed) {
        Map<Integer, GuiDefinition.SkippedLayer> skippedAt = new HashMap<>();
        for (GuiDefinition.SkippedLayer skip : gui.skipped()) {
            skippedAt.put(skip.index(), skip);
        }
        var out = new ArrayList<LayerRender>();
        int next = 0;
        int authored = gui.layers().size() + gui.skipped().size();
        for (int index = 0; index < authored; index++) {
            GuiDefinition.SkippedLayer skip = skippedAt.get(index);
            if (skip != null) {
                out.add(new LayerRender(skip.layerId(), null, -1, true, skip.reason()));
            } else {
                GuiCompilation.LayerReport report = placed.get(next++);
                out.add(new LayerRender(report.id(), report.target(), report.rank(),
                        report.skipped(), report.reason()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // helpers

    /** Whether an authored {@code type:} names a registered component (a bare name is {@code materione:}). */
    private boolean knownComponent(String authored) {
        try {
            NamespacedId id = authored.contains(":")
                    ? NamespacedId.parse(authored)
                    : NamespacedId.of(GuiType.BUILTIN_NAMESPACE, authored);
            return components.get(id).isPresent();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean isItemType(Object authored) {
        return "item".equals(authored) || "materione:item".equals(authored);
    }

    /** The pack resource a layer's {@code item:} names, when it is one of this pack's (a bare id is this pack's). */
    private static ResourceRef itemResource(String reference, String pack, List<ResourceRef> resources) {
        int colon = reference.indexOf(':');
        String namespace = colon < 0 ? pack : reference.substring(0, colon);
        String id = colon < 0 ? reference : reference.substring(colon + 1);
        if (!namespace.equals(pack)) {
            return null;
        }
        for (ResourceRef resource : resources) {
            if (resource.resourceId().equals(id)) {
                return resource;
            }
        }
        return null;
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> source) {
        var out = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    /** A YAML document's data as a string-keyed mapping, or an empty one. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(Object data) {
        return data instanceof Map<?, ?> mapping
                ? (Map<String, Object>) LosslessYaml.stringKeyedDeep(mapping) : Map.of();
    }

    private static String textOf(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** A whole number as authored (YAML may read it as any numeric type), or null. */
    private static Integer wholeNumber(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal) && decimal == Math.rint(decimal)
                    && decimal >= Integer.MIN_VALUE && decimal <= Integer.MAX_VALUE) {
                return (int) decimal;
            }
        }
        return null;
    }

    private static <T> ApiResult<T> conflict(String path, Integer current, int expected) {
        return new ApiResult<T>(false, null, List.of(new Diagnostic(
                DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT, Severity.WARN,
                path + " is at revision " + current + ", not " + expected,
                null, null, "re-read the GUI and re-apply your change")));
    }

    private static <T> ApiResult<T> badRequest(String message) {
        return new ApiResult<T>(false, null, List.of(Diagnostic.error(
                DiagnosticCodes.CONTENT_SCHEMA_INVALID, message)));
    }
}
