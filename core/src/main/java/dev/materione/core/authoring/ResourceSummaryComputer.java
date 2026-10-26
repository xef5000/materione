package dev.materione.core.authoring;

import dev.materione.api.NamespacedId;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.ResourceHealth;
import dev.materione.api.authoring.AuthoringApi.ResourceRender;
import dev.materione.api.authoring.AuthoringApi.ResourceSummary;
import dev.materione.api.authoring.AuthoringApi.ResourceSummaryPage;
import dev.materione.api.authoring.AuthoringApi.ResourceVisual;
import dev.materione.api.authoring.AuthoringApi.VisualKind;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.ResourcePresentation;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.core.document.AssetStore;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.LosslessYamlDocument;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** A bounded, read-only projection of canonical documents and registered type parsers. */
final class ResourceSummaryComputer {

    private static final List<String> TEXTURE_EXTENSIONS = List.of("png", "jpg", "jpeg");
    static final long DEFAULT_PROJECTION_BUDGET_BYTES = 16L * 1024 * 1024;
    private static final long SUMMARY_CANDIDATE_BASE_BYTES = 288;
    private static final long REFERENCE_INDEX_ENTRY_BYTES = 64;
    private static final int MAX_PRESENTATION_REFERENCE_DEPTH = 32;

    private final DocumentStore documents;
    private final AssetStore assets;
    private final Supplier<List<ContentType>> contentTypes;
    private final long projectionBudgetBytes;
    private final Object cacheLock = new Object();
    private SummarySnapshot cachedSnapshot;
    private FilteredResources cachedFilteredResources;
    private String cachedFailurePack;
    private ProjectionBudgetExceededException cachedFailure;

    ResourceSummaryComputer(
            DocumentStore documents, AssetStore assets, Supplier<List<ContentType>> contentTypes) {
        this(documents, assets, contentTypes, DEFAULT_PROJECTION_BUDGET_BYTES);
    }

    private static boolean hasTextureExtension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot <= slash) {
            return false;
        }
        return TEXTURE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static ReferenceKey resourceKey(DocumentStore.ResourceRef resource) {
        try {
            return new ReferenceKey(
                    resource.type(), NamespacedId.of(resource.pack(), resource.resourceId()));
        } catch (IllegalArgumentException malformedId) {
            return null;
        }
    }

    ResourceSummaryComputer(
            DocumentStore documents,
            AssetStore assets,
            Supplier<List<ContentType>> contentTypes,
            long projectionBudgetBytes) {
        this.documents = documents;
        this.assets = assets;
        this.contentTypes = contentTypes == null ? List::of : contentTypes;
        if (projectionBudgetBytes < 1) {
            throw new IllegalArgumentException("summary projection budget must be positive");
        }
        this.projectionBudgetBytes = projectionBudgetBytes;
    }

    ResourceSummaryPage summarize(AuthoringApi.ResourceFilter filter, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > AuthoringApi.MAX_SUMMARY_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "summary page requires offset >= 0 and limit between 1 and "
                            + AuthoringApi.MAX_SUMMARY_PAGE_SIZE);
        }
        AuthoringApi.ResourceFilter safe = filter == null
                ? AuthoringApi.ResourceFilter.all() : filter;
        if (safe.pack() == null || safe.pack().isBlank()) {
            throw new IllegalArgumentException("a pack is required for resource summaries");
        }

        SummarySnapshot snapshot = snapshot(safe.pack());
        String query = safe.query() == null || safe.query().isBlank()
                ? null : safe.query().toLowerCase(Locale.ROOT);
        List<SummaryCandidate> selected = filteredResources(snapshot, safe.type(), query);
        int total = selected.size();
        int start = Math.min(offset, total);
        int end = (int) Math.min((long) start + limit, total);
        List<ResourceSummary> page = new ArrayList<>(end - start);
        for (SummaryCandidate row : selected.subList(start, end)) {
            page.add(summarize(row, snapshot));
        }
        Integer nextOffset = end < total ? end : null;
        return new ResourceSummaryPage(page, total, nextOffset);
    }

    void invalidate() {
        synchronized (cacheLock) {
            cachedSnapshot = null;
            cachedFilteredResources = null;
            cachedFailurePack = null;
            cachedFailure = null;
        }
    }

    private SummarySnapshot snapshot(String pack) {
        synchronized (cacheLock) {
            if (cachedSnapshot != null && cachedSnapshot.pack().equals(pack)) {
                return cachedSnapshot;
            }
            if (cachedFailure != null && pack.equals(cachedFailurePack)) {
                throw cachedFailure;
            }
            cachedSnapshot = null;
            cachedFilteredResources = null;
            cachedFailurePack = null;
            cachedFailure = null;
            SummarySnapshot computed;
            try {
                computed = buildSnapshot(pack);
            } catch (ProjectionBudgetExceededException e) {
                cachedFailurePack = pack;
                cachedFailure = e;
                throw e;
            }
            cachedSnapshot = computed;
            return computed;
        }
    }

    private List<SummaryCandidate> filteredResources(
            SummarySnapshot snapshot, String type, String query) {
        synchronized (cacheLock) {
            if (cachedSnapshot == snapshot && cachedFilteredResources != null
                    && java.util.Objects.equals(cachedFilteredResources.type(), type)
                    && java.util.Objects.equals(cachedFilteredResources.query(), query)) {
                return cachedFilteredResources.resources();
            }
            List<SummaryCandidate> selected = snapshot.resources().stream()
                    .filter(row -> type == null || row.resource().type().equalsIgnoreCase(type))
                    .filter(row -> query == null || queryMatches(row.resource(), query))
                    .toList();
            if (cachedSnapshot == snapshot) {
                cachedFilteredResources = new FilteredResources(type, query, selected);
            }
            return selected;
        }
    }

    private SummarySnapshot buildSnapshot(String pack) {
        ProjectionBudget budget = new ProjectionBudget(projectionBudgetBytes);
        Map<String, Map<String, Object>> templates = templates(budget).definitions();
        Map<String, ContentType> typesByDirectory = typesByDirectory();
        List<SummaryCandidate> resources = new ArrayList<>();
        Map<ReferenceKey, SummaryCandidate> referenceCandidates = new LinkedHashMap<>();
        ArrayDeque<PendingReference> pendingReferences = new ArrayDeque<>();
        Set<ReferenceKey> discoveredReferences = new HashSet<>();
        Set<ReferenceKey> followedCandidates = new HashSet<>();
        Set<ReferencePartition> scannedPartitions = new HashSet<>();
        documents.forEachResourceDocument(
                pack, null, row -> {
                    SummaryCandidate candidate = prepareBounded(row, templates, typesByDirectory, budget);
                    resources.add(candidate);
                    indexReferenceCandidate(candidate, referenceCandidates,
                            pendingReferences, discoveredReferences, followedCandidates, budget, true, 0);
                },
                budget);
        while (!pendingReferences.isEmpty()) {
            PendingReference pending = pendingReferences.removeFirst();
            ReferenceKey key = pending.key();
            SummaryCandidate indexed = referenceCandidates.get(key);
            if (indexed == null
                    && !key.id().isReservedNamespace()
                    && !pack.equals(key.id().namespace())
                    && typesByDirectory.containsKey(key.type())) {
                ReferencePartition partition = new ReferencePartition(key.id().namespace(), key.type());
                if (scannedPartitions.add(partition)) {
                    budget.consume(REFERENCE_INDEX_ENTRY_BYTES);
                    documents.forEachResourceDocument(
                            partition.pack(),
                            partition.type(),
                            row -> {
                                SummaryCandidate candidate =
                                        prepareBounded(row, templates, typesByDirectory, budget);
                                indexReferenceCandidate(
                                        candidate, referenceCandidates, pendingReferences,
                                        discoveredReferences, followedCandidates, budget, false, 0);
                            },
                            budget);
                }
                indexed = referenceCandidates.get(key);
            }
            if (indexed != null) {
                followCandidateReference(indexed, pendingReferences,
                        discoveredReferences, followedCandidates, budget, pending.depth());
            }
        }
        resources.sort(Comparator
                .comparing((SummaryCandidate row) -> row.resource().pack())
                .thenComparing(row -> row.resource().type())
                .thenComparing(row -> row.resource().path())
                .thenComparing(row -> row.resource().resourceId()));
        List<SummaryCandidate> frozen = List.copyOf(resources);
        return new SummarySnapshot(
                pack, frozen, Collections.unmodifiableMap(referenceCandidates));
    }

    private SummaryCandidate prepareBounded(
            DocumentStore.ResourceDocument row,
            Map<String, Map<String, Object>> templates,
            Map<String, ContentType> typesByDirectory,
            ProjectionBudget budget) {
        budget.consume(SUMMARY_CANDIDATE_BASE_BYTES);
        SummaryCandidate candidate = prepare(row, templates, typesByDirectory);
        budget.consume(estimateSize(candidate) - SUMMARY_CANDIDATE_BASE_BYTES);
        return candidate;
    }

    private static void indexReferenceCandidate(
            SummaryCandidate candidate,
            Map<ReferenceKey, SummaryCandidate> candidates,
            ArrayDeque<PendingReference> pending,
            Set<ReferenceKey> discovered,
            Set<ReferenceKey> followed,
            ProjectionBudget budget,
            boolean followReference,
            int referenceDepth) {
        DocumentStore.ResourceRef resource = candidate.resource();
        final NamespacedId id;
        try {
            id = NamespacedId.of(resource.pack(), resource.resourceId());
        } catch (IllegalArgumentException malformedId) {
            return;
        }
        ReferenceKey key = new ReferenceKey(resource.type(), id);
        SummaryCandidate indexed = candidates.get(key);
        if (indexed == null) {
            budget.consume(REFERENCE_INDEX_ENTRY_BYTES);
            candidates.put(key, candidate);
            indexed = candidate;
        }
        if (followReference && indexed == candidate) {
            followCandidateReference(indexed, pending, discovered, followed, budget, referenceDepth);
        }
    }

    private static void followCandidateReference(
            SummaryCandidate candidate,
            ArrayDeque<PendingReference> pending,
            Set<ReferenceKey> discovered,
            Set<ReferenceKey> followed,
            ProjectionBudget budget,
            int referenceDepth) {
        ResourcePresentation.Reference reference = candidate.presentation() == null
                ? null : candidate.presentation().reference();
        if (reference == null || referenceDepth >= MAX_PRESENTATION_REFERENCE_DEPTH) {
            return;
        }
        ReferenceKey owner = resourceKey(candidate.resource());
        if (owner == null || candidate.presentation() == null
                || !followed.add(owner)) {
            return;
        }
        budget.consume(REFERENCE_INDEX_ENTRY_BYTES);
        ReferenceKey target = new ReferenceKey(reference.type(), reference.id());
        if (discovered.add(target)) {
            budget.consume(REFERENCE_INDEX_ENTRY_BYTES);
            pending.addLast(new PendingReference(target, referenceDepth + 1));
        }
    }

    private SummaryCandidate prepare(
            DocumentStore.ResourceDocument row,
            Map<String, Map<String, Object>> templates,
            Map<String, ContentType> typesByDirectory) {
        DocumentStore.ResourceRef ref = row.resource();
        ResourceHealth health = ResourceHealth.UNKNOWN;
        String healthMessage = "pipeline validation and apply freshness are unknown";
        Map<String, Object> definition = null;
        if (row.readError() != null || row.document() == null) {
            healthMessage = row.readError() == null
                    ? "canonical document could not be read" : row.readError();
        } else if (row.document().parseError().isPresent()) {
            health = ResourceHealth.ERROR;
            healthMessage = "canonical document could not be parsed";
        } else {
            definition = mapping(row.document(), ref.resourceId());
            if (definition == null) {
                health = ResourceHealth.ERROR;
                healthMessage = "resource definition is not a mapping";
            }
        }

        String name = humanize(ref.resourceId());
        String material = null;
        ResourcePresentation presentation = null;
        if (definition != null && health != ResourceHealth.ERROR) {
            ContentType type = typesByDirectory.get(ref.type());
            if (type != null) {
                try {
                    NamespacedId id = NamespacedId.of(ref.pack(), ref.resourceId());
                    var parsed = type.parse(id, definition, templates);
                    if (parsed.isEmpty()) {
                        health = ResourceHealth.ERROR;
                        healthMessage = "registered content type could not resolve this definition";
                    } else {
                        presentation = type.presentation(parsed.get()).orElse(null);
                        String schemaName = schemaName(type, definition);
                        if (presentation != null && nonBlank(presentation.name()) != null) {
                            name = presentation.name();
                        } else if (schemaName != null) {
                            name = schemaName;
                        }
                        if (presentation != null) {
                            material = nonBlank(presentation.material());
                        }
                    }
                } catch (IllegalArgumentException e) {
                    health = ResourceHealth.ERROR;
                    healthMessage = "resource identity or definition is invalid";
                } catch (RuntimeException e) {
                    healthMessage = "registered content type could not be evaluated";
                }
            }
        }
        return new SummaryCandidate(ref, name, material, presentation, health, healthMessage);
    }

    private ResourceSummary summarize(SummaryCandidate candidate, SummarySnapshot snapshot) {
        ResourceHealth health = candidate.health();
        String healthMessage = candidate.healthMessage();
        ResourceVisual visual = null;
        if (candidate.presentation() != null && health != ResourceHealth.ERROR) {
            VisualResolution resolved = resolveVisual(candidate, snapshot, new HashSet<>(), 0);
            visual = resolved.visual();
            if (resolved.error() != null) {
                health = ResourceHealth.ERROR;
                healthMessage = resolved.error();
            } else if (resolved.unavailable()) {
                healthMessage = "visual reference could not be safely checked";
            }
        }
        DocumentStore.ResourceRef ref = candidate.resource();
        ResourceRender render = health == ResourceHealth.ERROR || candidate.presentation() == null
                ? null : resolveRender(ref, candidate.presentation());
        return new ResourceSummary(
                ref.pack(), ref.type(), ref.path(), ref.resourceId(),
                nonBlank(candidate.name()) == null ? ref.resourceId() : candidate.name(),
                candidate.material(), visual, health, healthMessage, render);
    }

    /**
     * The live-render input: the type's generated model when it compiles one
     * (texture references stay logical — the editor reads them as assets),
     * else a readable hand-authored model asset. Vanilla and unreadable
     * models have no render; the static visual still applies.
     */
    private ResourceRender resolveRender(
            DocumentStore.ResourceRef owner, ResourcePresentation presentation) {
        if (presentation.generatedModel() != null) {
            return new ResourceRender(owner.pack(), null, presentation.generatedModel());
        }
        if (nonBlank(presentation.model()) == null) {
            return null;
        }
        AssetReference reference = assetReference(presentation.model(), owner.pack());
        if (reference == null || reference.pack().equals("minecraft")
                || !documents.hasSummaryPack(reference.pack())) {
            return null;
        }
        String modelPath = "assets/models/" + reference.path() + ".json";
        return inspect(reference.pack(), modelPath) == AssetStore.ReadableAssetState.PRESENT
                ? new ResourceRender(reference.pack(), modelPath, null) : null;
    }

    /**
     * Every readable template by full id, with the pack-relative document that
     * defines it — the same pool, and the same winner on duplicate ids, the
     * summaries resolve against. Bounded by the projection budget.
     */
    TemplatePool templatePool() {
        return templates(new ProjectionBudget(projectionBudgetBytes));
    }

    /** Template definitions by id, plus {@code [pack, path]} of each defining document. */
    record TemplatePool(Map<String, Map<String, Object>> definitions, Map<String, List<String>> sources) {}

    private TemplatePool templates(ProjectionBudget budget) {
        Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
        Map<String, String> sourcePaths = new HashMap<>();
        Map<String, String> sourcePacks = new HashMap<>();
        documents.forEachResourceDocument(null, "templates", row -> {
            if (row.document() == null || row.document().parseError().isPresent()) {
                return;
            }
            Map<String, Object> definition = mapping(row.document(), row.resource().resourceId());
            if (definition == null) {
                return;
            }
            try {
                String id = NamespacedId.of(
                        row.resource().pack(), row.resource().resourceId()).toString();
                String existingPath = sourcePaths.get(id);
                if (existingPath == null || row.resource().path().compareTo(existingPath) > 0) {
                    budget.consume(128);
                    budget.consume(estimateTemplateSize(id, row.resource().path(), definition) - 128);
                    templates.put(id, LosslessYaml.stringKeyed(definition));
                    sourcePaths.put(id, row.resource().path());
                    sourcePacks.put(id, row.resource().pack());
                }
            } catch (IllegalArgumentException ignored) {
                // An invalid template id cannot be a valid parser target.
            }
        }, budget);
        Map<String, List<String>> sources = new HashMap<>();
        sourcePaths.forEach((id, path) -> sources.put(id, List.of(sourcePacks.get(id), path)));
        return new TemplatePool(Map.copyOf(templates), Map.copyOf(sources));
    }

    private Map<String, ContentType> typesByDirectory() {
        Map<String, ContentType> byDirectory = new HashMap<>();
        List<ContentType> registered = contentTypes.get();
        if (registered != null) {
            for (ContentType type : registered) {
                if (type != null && type.directory() != null && !type.directory().isBlank()) {
                    byDirectory.put(type.directory(), type);
                }
            }
        }
        return Map.copyOf(byDirectory);
    }

    private static long estimateSize(SummaryCandidate candidate) {
        DocumentStore.ResourceRef ref = candidate.resource();
        ResourcePresentation presentation = candidate.presentation();
        long chars = length(ref.pack()) + length(ref.type()) + length(ref.path())
                + length(ref.resourceId()) + length(candidate.name()) + length(candidate.material())
                + (presentation == null ? 0
                        : length(presentation.name()) + length(presentation.material())
                                + length(presentation.texture()) + length(presentation.model())
                                + (presentation.generatedModel() == null ? 0
                                        : estimateValue(presentation.generatedModel(), new IdentityHashMap<>())
                                                / Character.BYTES)
                                + (presentation.reference() == null ? 0
                                        : length(presentation.reference().type())
                                                + length(presentation.reference().id().toString())))
                + length(candidate.healthMessage());
        return SUMMARY_CANDIDATE_BASE_BYTES + chars * Character.BYTES;
    }

    private static long estimateTemplateSize(String id, String path, Object value) {
        return 128L + (id.length() + path.length()) * Character.BYTES
                + estimateValue(value, new IdentityHashMap<>());
    }

    private static long estimateValue(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            return 8;
        }
        if (value instanceof String text) {
            return 40L + text.length() * Character.BYTES;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return 24;
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            return 0;
        }
        if (value instanceof Map<?, ?> map) {
            long size = 64;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                size += 32L + estimateValue(entry.getKey(), seen) + estimateValue(entry.getValue(), seen);
            }
            return size;
        }
        if (value instanceof Iterable<?> values) {
            long size = 48;
            for (Object item : values) {
                size += 16L + estimateValue(item, seen);
            }
            return size;
        }
        return 32;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static String schemaName(ContentType type, Map<String, Object> definition) {
        for (ParameterSchema parameter : type.describe().parameters()) {
            String label = parameter.editor().label().toLowerCase(Locale.ROOT);
            if (!parameter.editor().section().equals("identity")
                    || !(label.contains("name") || label.contains("title"))) {
                continue;
            }
            String value = nonBlank(definition.get(parameter.id()));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private VisualResolution resolveVisual(
            SummaryCandidate candidate,
            SummarySnapshot snapshot,
            Set<ReferenceKey> visited,
            int depth) {
        ResourcePresentation presentation = candidate.presentation();
        if (presentation == null) {
            return VisualResolution.none();
        }
        if (nonBlank(presentation.texture()) != null || nonBlank(presentation.model()) != null) {
            return resolveDirectVisual(candidate.resource(), presentation);
        }
        ResourcePresentation.Reference reference = presentation.reference();
        if (reference == null) {
            return VisualResolution.none();
        }
        if (depth >= MAX_PRESENTATION_REFERENCE_DEPTH) {
            return VisualResolution.unavailableResult();
        }
        ReferenceKey ownerKey = resourceKey(candidate.resource());
        if (ownerKey != null && !visited.add(ownerKey)) {
            return VisualResolution.error("presentation reference cycle");
        }
        ReferenceKey targetKey = new ReferenceKey(reference.type(), reference.id());
        SummaryCandidate target = snapshot.referenceCandidates().get(targetKey);
        if (target == null) {
            return VisualResolution.error("presentation reference target is missing");
        }
        if (target.health() == ResourceHealth.ERROR) {
            return VisualResolution.error("presentation reference target is invalid");
        }
        VisualResolution resolved = resolveVisual(target, snapshot, visited, depth + 1);
        return resolved.visual() == null && resolved.error() == null && !resolved.unavailable()
                ? VisualResolution.unavailableResult()
                : resolved;
    }

    private VisualResolution resolveDirectVisual(
            DocumentStore.ResourceRef owner, ResourcePresentation presentation) {
        VisualResolution texture = nonBlank(presentation.texture()) == null
                ? VisualResolution.none()
                : resolveTexture(presentation.texture(), owner.pack());
        VisualResolution model = nonBlank(presentation.model()) == null
                ? VisualResolution.none()
                : resolveModel(presentation.model(), owner.pack());

        ResourceVisual visual = model.visual() != null ? model.visual() : texture.visual();
        String error = texture.error() != null ? texture.error() : model.error();
        return new VisualResolution(
                visual, error, texture.unavailable() || model.unavailable());
    }

    private VisualResolution resolveTexture(String raw, String defaultPack) {
        AssetReference reference = assetReference(raw, defaultPack);
        if (reference == null) {
            return VisualResolution.error("texture reference is not a safe asset path");
        }
        if (reference.pack().equals("minecraft")) {
            return VisualResolution.none();
        }
        if (!documents.hasSummaryPack(reference.pack())) {
            return VisualResolution.error("texture reference names an unavailable pack");
        }
        List<String> paths = hasTextureExtension(reference.path())
                ? List.of("assets/textures/" + reference.path())
                : TEXTURE_EXTENSIONS.stream()
                        .map(extension -> "assets/textures/" + reference.path() + "." + extension)
                        .toList();
        for (String path : paths) {
            AssetStore.ReadableAssetState state = inspect(reference.pack(), path);
            if (state == AssetStore.ReadableAssetState.PRESENT) {
                return VisualResolution.found(
                        new ResourceVisual(reference.pack(), path, VisualKind.TEXTURE));
            }
            if (state == AssetStore.ReadableAssetState.UNAVAILABLE) {
                return VisualResolution.unavailableResult();
            }
        }
        return VisualResolution.error("referenced texture asset is missing");
    }

    private VisualResolution resolveModel(String raw, String defaultPack) {
        AssetReference reference = assetReference(raw, defaultPack);
        if (reference == null) {
            return VisualResolution.error("model reference is not a safe asset path");
        }
        if (reference.pack().equals("minecraft")) {
            return VisualResolution.none();
        }
        if (!documents.hasSummaryPack(reference.pack())) {
            return VisualResolution.error("model reference names an unavailable pack");
        }
        String modelPath = "assets/models/" + reference.path() + ".json";
        AssetStore.ReadableAssetState modelState = inspect(reference.pack(), modelPath);
        if (modelState == AssetStore.ReadableAssetState.MISSING) {
            return VisualResolution.error("referenced model asset is missing");
        }
        if (modelState == AssetStore.ReadableAssetState.UNAVAILABLE) {
            return VisualResolution.unavailableResult();
        }
        String thumbnail = ".materione/thumbnails/" + reference.path() + ".png";
        AssetStore.ReadableAssetState thumbnailState = inspect(reference.pack(), thumbnail);
        if (thumbnailState == AssetStore.ReadableAssetState.PRESENT) {
            return VisualResolution.found(
                    new ResourceVisual(reference.pack(), thumbnail, VisualKind.MODEL_THUMBNAIL));
        }
        return thumbnailState == AssetStore.ReadableAssetState.UNAVAILABLE
                ? VisualResolution.unavailableResult() : VisualResolution.none();
    }

    private AssetStore.ReadableAssetState inspect(String pack, String path) {
        return assets == null ? AssetStore.ReadableAssetState.UNAVAILABLE
                : assets.inspectReadable(pack, path);
    }

    private static AssetReference assetReference(String raw, String defaultPack) {
        try {
            NamespacedId id = raw.contains(":")
                    ? NamespacedId.parse(raw) : NamespacedId.of(defaultPack, raw);
            if (id.value().contains("..") || id.value().startsWith("/")
                    || id.value().contains("\\") || !id.value().matches("[a-z0-9_./-]+")) {
                return null;
            }
            return new AssetReference(id.namespace(), id.value());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> mapping(LosslessYamlDocument document, String id) {
        Object value = document.data().get(id);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return LosslessYaml.stringKeyed(map);
    }

    private static boolean queryMatches(DocumentStore.ResourceRef ref, String query) {
        return (ref.pack() + ":" + ref.resourceId() + " " + ref.path())
                .toLowerCase(Locale.ROOT).contains(query);
    }

    private static String humanize(String id) {
        if (id == null || id.isBlank()) {
            return "Resource";
        }
        String value = id.substring(id.lastIndexOf('/') + 1)
                .replace('_', ' ').replace('-', ' ');
        StringBuilder result = new StringBuilder();
        for (String word : value.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? "Resource" : result.toString();
    }

    private static String nonBlank(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private record SummarySnapshot(
            String pack,
            List<SummaryCandidate> resources,
            Map<ReferenceKey, SummaryCandidate> referenceCandidates) {}

    private record FilteredResources(
            String type, String query, List<SummaryCandidate> resources) {}

    private record SummaryCandidate(
            DocumentStore.ResourceRef resource,
            String name,
            String material,
            ResourcePresentation presentation,
            ResourceHealth health,
            String healthMessage) {}

    private record AssetReference(String pack, String path) {}

    private record ReferenceKey(String type, NamespacedId id) {}

    private record PendingReference(ReferenceKey key, int depth) {}

    private record ReferencePartition(String pack, String type) {}

    private record VisualResolution(ResourceVisual visual, String error, boolean unavailable) {
        static VisualResolution found(ResourceVisual visual) {
            return new VisualResolution(visual, null, false);
        }

        static VisualResolution error(String message) {
            return new VisualResolution(null, message, false);
        }

        static VisualResolution unavailableResult() {
            return new VisualResolution(null, null, true);
        }

        static VisualResolution none() {
            return new VisualResolution(null, null, false);
        }
    }

    static final class ProjectionBudgetExceededException extends RuntimeException {
        ProjectionBudgetExceededException(long limitBytes) {
            super("resource summary exceeded its bounded read/projection budget of "
                    + limitBytes + " bytes");
        }
    }

    private static final class ProjectionBudget implements DocumentStore.SummaryReadBudget {
        private final long limitBytes;
        private long usedBytes;

        ProjectionBudget(long limitBytes) {
            this.limitBytes = limitBytes;
        }

        @Override
        public long remainingBytes() {
            return limitBytes - usedBytes;
        }

        @Override
        public void consume(long bytes) {
            if (bytes < 0 || bytes > remainingBytes()) {
                throw new ProjectionBudgetExceededException(limitBytes);
            }
            usedBytes += bytes;
        }

        @Override
        public void requireRemainingCapacity() {
            if (remainingBytes() <= 0) {
                throw new ProjectionBudgetExceededException(limitBytes);
            }
        }
    }
}
