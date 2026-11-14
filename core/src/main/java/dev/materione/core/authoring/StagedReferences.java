package dev.materione.core.authoring;

import dev.materione.api.NamespacedId;
import dev.materione.api.authoring.TransactionResult.OperationDiagnostic;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.LosslessYamlDocument;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PackAssets;
import dev.materione.core.pipeline.PackLayout;
import dev.materione.core.pipeline.PackResources;
import dev.materione.core.pipeline.ParsedContent;
import dev.materione.core.pipeline.validate.ReferenceValidator;
import dev.materione.core.pipeline.validate.ValidationEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Master section 5.4 level 3 over a transaction's <em>result</em>: {@link ReferenceValidator}, the one reference
 * validator the pipeline runs (D6), over the parsed pack set with the staged documents laid over the one pack the
 * list writes. Nothing is written; the pack's stored documents are read through the store and everything else is the
 * last pipeline run's parse ({@link ParsedContent}), so a reference into another pack resolves against that pack's
 * stored state (the one-pack rule limits writes, not reads).
 *
 * <h2>Scope</h2>
 * Resources in documents the list touched are validated in full. Of the untouched ones, those that name an id a
 * touched document declared (before or after the list) are found by a name index over their string values reduced to
 * the part after the last colon, followed transitively, and validated before and after; only the findings the list
 * <em>adds</em> are reported, so a resource that was already broken is not charged to a list that did not break it.
 * The rest are not read again.
 *
 * <p>That the index misses nothing is a tested property, not an argument:
 * {@code StagedReferencesDifferentialTest} holds this check equal, over random edit lists, to {@link #checkEverything}
 * (every untouched resource of every pack, before and after, no index) on a fixture that exercises every reference
 * form {@code ReferenceValidator} resolves. The index assumes each reference is a whole string value; a new reference
 * form in the validator (a map key, an id inside a longer string) must be added to that fixture, where a miss
 * fails the test.
 */
final class StagedReferences {

    /** One document of the working set: its stored text, its text now, and the last operation that touched it. */
    record StagedDocument(String path, String original, String text, boolean touched, int operation) {}

    /** What the check found, and how much it looked at. */
    record Outcome(List<OperationDiagnostic> diagnostics, int validated, int impacted) {}

    /**
     * The parsed documents of the packs a transaction staged, kept between dry runs and keyed by what the file
     * system says about each file (size and modification time), so a repeat dry run on a pack parses only what
     * changed. Rebuilt wholesale from each walk, so it never holds more than the pack has.
     */
    static final class DocumentCache {
        record Entry(long size, long modified, LosslessYamlDocument document) {}

        private final Map<String, Map<Path, Entry>> packs = new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * The document at {@code file}: the cached parse while size and modification time are unchanged; else the
         * last pipeline run's parse when the file still holds exactly its text; else a fresh parse.
         */
        Entry get(Map<Path, Entry> previous, Map<Path, LosslessYamlDocument> lastRun, Path file) throws IOException {
            var attributes = java.nio.file.Files.readAttributes(
                    file, java.nio.file.attribute.BasicFileAttributes.class);
            long modified = attributes.lastModifiedTime().toMillis();
            Entry entry = previous.get(file);
            if (entry != null && entry.size() == attributes.size() && entry.modified() == modified) {
                return entry;
            }
            String text = PackResources.readOrEmpty(file);
            LosslessYamlDocument parsed = lastRun.get(file.toAbsolutePath().normalize());
            if (parsed == null || !parsed.save().equals(text)) {
                parsed = LosslessYaml.parse(text);
            }
            return new Entry(attributes.size(), modified, parsed);
        }

        Map<Path, Entry> previous(String pack) {
            return packs.getOrDefault(pack, Map.of());
        }

        void replace(String pack, Map<Path, Entry> walked) {
            packs.put(pack, walked);
        }
    }

    private final String pack;
    private final Path packDirectory;
    private final ParsedContent parsed;
    private final DocumentCache cache;
    private final dev.materione.core.pipeline.validate.StaticReferences staticReferences;

    StagedReferences(String pack, Path packDirectory, ParsedContent parsed, DocumentCache cache,
            dev.materione.core.pipeline.validate.StaticReferences staticReferences) {
        this.staticReferences = staticReferences;
        this.pack = pack;
        this.packDirectory = packDirectory;
        this.parsed = parsed;
        this.cache = cache;
    }

    /**
     * The pack's stored documents by the pipeline's own discovery rule ({@link PackLayout}): each parsed once per
     * change. {@code result} gets the resources of every document the list did not stage.
     */
    private void readStored(
            Map<String, StagedDocument> byPath, List<ContentResource> stored, List<ContentResource> result) {
        Map<Path, DocumentCache.Entry> previous = cache.previous(pack);
        Map<Path, DocumentCache.Entry> walked = new HashMap<>();
        Map<Path, LosslessYamlDocument> lastRun = new HashMap<>();
        if (parsed != null) {
            parsed.packs().stream().filter(p -> p.id().equals(pack)).findFirst().ifPresent(p -> {
                for (ContentResource resource : p.resources()) {
                    lastRun.put(resource.sourceFile().toAbsolutePath().normalize(), resource.document());
                }
            });
        }
        for (String directory : PackLayout.CONTENT_DIRS) {
            for (Path file : PackLayout.contentDocuments(packDirectory, directory, failure -> { })) {
                try {
                    DocumentCache.Entry entry = cache.get(previous, lastRun, file);
                    walked.put(file, entry);
                    String path = packDirectory.relativize(file).toString().replace('\\', '/');
                    for (ContentResource resource
                            : PackResources.declared(pack, directory, file, entry.document()).resources()) {
                        stored.add(resource);
                        if (!byPath.containsKey(path)) {
                            result.add(resource);
                        }
                    }
                } catch (IOException unreadable) {
                    // not even a stat: the file declares nothing
                }
            }
        }
        cache.replace(pack, walked);
    }

    Outcome check(List<StagedDocument> staged) throws IOException {
        return check(staged, false);
    }

    /**
     * What the check marks as pre-existing (#372, an edit; a revert passes false and stays strict): a finding on a
     * resource the list wrote that the same resource already had as stored, before the list. The key is the one the
     * findings are deduplicated by (resource, code, message, target), so a finding the list moves or rewords counts
     * as new.
     */
    private static final boolean BASELINE = true;

    /**
     * The oracle for the scope claim: every untouched resource of every pack is validated before and after, with no
     * index deciding which ones matter. {@code StagedReferencesDifferentialTest} holds {@link #check} equal to it.
     */
    Outcome checkEverything(List<StagedDocument> staged) throws IOException {
        return check(staged, true);
    }

    /**
     * The pack as stored and as the list leaves it: one view, built once per dry run, that the references stage and
     * the logic check both read (D6: no second mechanism for "the pack after the list").
     */
    static final class View {
        private final List<ContentResource> stored = new ArrayList<>();
        private final List<ContentResource> result = new ArrayList<>();
        private final Map<ContentResource, Integer> touchedOperation = new IdentityHashMap<>();
        private final Set<ContentResource> touchedResources = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<ContentResource, String> touchedOriginal = new IdentityHashMap<>();
        private final Map<ContentResource, String> touchedPath = new IdentityHashMap<>();
        private Map<String, ContentResource> declarations;
        private Map<String, ContentResource> storedDeclarations;
    }

    View view(List<StagedDocument> staged) throws IOException {
        Map<String, StagedDocument> byPath = new LinkedHashMap<>();
        staged.forEach(document -> byPath.put(document.path(), document));
        View view = new View();
        readStored(byPath, view.stored, view.result);
        for (StagedDocument document : staged) {
            if (document.text() == null) {
                continue;
            }
            for (ContentResource resource : resourcesOf(document.path(), document.text())) {
                view.result.add(resource);
                if (document.touched()) {
                    view.touchedResources.add(resource);
                    view.touchedOperation.put(resource, document.operation());
                    if (document.original() != null) {
                        view.touchedOriginal.put(resource, document.original());
                    }
                    view.touchedPath.put(resource, document.path());
                }
            }
        }
        return view;
    }

    /**
     * The resource of {@code contentType} with this id in the pack as the list leaves it: this pack's documents
     * with the staged ones laid over, every other pack as the last pipeline run parsed it. Lookups by id, for the
     * checks that resolve declared content one at a time (the logic check's variables).
     */
    Optional<ContentResource> declared(View view, String contentType, NamespacedId id) {
        return declared(view, contentType, id, false);
    }

    /** As above; {@code stored} reads this pack as it is on disk, before the list (the baseline of a before/after check). */
    Optional<ContentResource> declared(View view, String contentType, NamespacedId id, boolean stored) {
        if (stored) {
            if (view.storedDeclarations == null) {
                Map<String, ContentResource> index = new HashMap<>();
                if (parsed != null) {
                    for (ContentPack other : parsed.packs()) {
                        if (!other.id().equals(pack)) {
                            other.resources().forEach(resource ->
                                    index.putIfAbsent(resource.contentType() + "|" + resource.id(), resource));
                        }
                    }
                }
                view.stored.forEach(resource -> index.put(resource.contentType() + "|" + resource.id(), resource));
                view.storedDeclarations = index;
            }
            return Optional.ofNullable(view.storedDeclarations.get(contentType + "|" + id));
        }
        if (view.declarations == null) {
            Map<String, ContentResource> index = new HashMap<>();
            if (parsed != null) {
                for (ContentPack other : parsed.packs()) {
                    if (!other.id().equals(pack)) {
                        other.resources().forEach(resource -> index.putIfAbsent(resource.contentType() + "|" + resource.id(), resource));
                    }
                }
            }
            view.result.forEach(resource -> index.put(resource.contentType() + "|" + resource.id(), resource));
            view.declarations = index;
        }
        return Optional.ofNullable(view.declarations.get(contentType + "|" + id));
    }

    Outcome check(View staging, List<StagedDocument> staged) throws IOException {
        return check(staging, staged, false, BASELINE);
    }

    /** @param baseline false for a revert: every finding on a restored resource refuses it, whatever it had before */
    Outcome check(View staging, List<StagedDocument> staged, boolean baseline) throws IOException {
        return check(staging, staged, false, baseline);
    }

    private Outcome check(List<StagedDocument> staged, boolean everything) throws IOException {
        return check(view(staged), staged, everything, BASELINE);
    }

    private Outcome check(View staging, List<StagedDocument> staged, boolean everything, boolean baseline)
            throws IOException {
        List<ContentResource> stored = staging.stored;
        List<ContentResource> result = staging.result;
        Map<ContentResource, Integer> touchedOperation = staging.touchedOperation;
        Set<ContentResource> touchedResources = staging.touchedResources;

        ParsedContent view = parsed == null ? ParsedContent.EMPTY : parsed;
        ContentPack manifestSource = view.packs().stream().filter(p -> p.id().equals(pack)).findFirst().orElse(null);
        Set<NamespacedId> assets = new LinkedHashSet<>();
        Map<NamespacedId, int[]> sizes = new LinkedHashMap<>();
        view.knownAssets().stream().filter(id -> !id.namespace().equals(pack)).forEach(assets::add);
        view.paintingSizes().forEach((id, size) -> {
            if (!id.namespace().equals(pack)) {
                sizes.put(id, size);
            }
        });
        PackAssets.index(packDirectory, pack, assets, sizes);

        ValidationEnvironment after = environment(view, manifestSource, result, assets, sizes);
        ReferenceValidator validator = new ReferenceValidator();
        List<OperationDiagnostic> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. the resources the list wrote. A finding the same resource already had as stored is pre-existing: a
        // warning that does not refuse the list. The baseline is measured only for a resource that has a finding.
        int validated = 0;
        ValidationEnvironment storedEnvironment = null;
        for (ContentResource resource : result) {
            if (touchedResources.contains(resource)) {
                validated++;
                int operation = touchedOperation.get(resource);
                List<Diagnostic> own = validator.validate(resource, after);
                Set<String> old = Set.of();
                String original = baseline && !own.isEmpty() ? staging.touchedOriginal.get(resource) : null;
                if (original != null) {
                    if (storedEnvironment == null) {
                        storedEnvironment = environment(view, manifestSource, stored, assets, sizes);
                    }
                    old = new HashSet<>();
                    for (ContentResource before : resourcesOf(staging.touchedPath.get(resource), original)) {
                        if (before.id().equals(resource.id()) && before.contentType().equals(resource.contentType())) {
                            for (Diagnostic diagnostic : validator.validate(before, storedEnvironment)) {
                                old.add(key(resource, diagnostic));
                            }
                        }
                    }
                }
                for (Diagnostic diagnostic : own) {
                    if (old.contains(key(resource, diagnostic))) {
                        addPreexisting(found, seen, operation, diagnostic, resource);
                    } else {
                        add(found, seen, operation, diagnostic, resource);
                    }
                }
            }
        }

        // 2. the resources the list did not write, that name something it changed
        Map<String, Integer> changedBy = new LinkedHashMap<>();
        for (StagedDocument document : staged) {
            if (!document.touched()) {
                continue;
            }
            for (String text : new String[] {document.original(), document.text()}) {
                if (text != null) {
                    for (ContentResource resource : resourcesOf(document.path(), text)) {
                        changedBy.putIfAbsent(resource.id().value(), document.operation());
                    }
                }
            }
        }
        List<ContentResource> impacted = everything
                ? untouched(view, result, touchedResources, pack)
                : impacted(view, result, touchedResources, pack, changedBy.keySet());
        if (!impacted.isEmpty()) {
            if (storedEnvironment == null) {
                storedEnvironment = environment(view, manifestSource, stored, assets, sizes);
            }
            ValidationEnvironment before = storedEnvironment;
            Set<String> existing = new HashSet<>();
            for (ContentResource resource : impacted) {
                for (Diagnostic diagnostic : validator.validate(resource, before)) {
                    existing.add(key(resource, diagnostic));
                }
            }
            for (ContentResource resource : impacted) {
                for (Diagnostic diagnostic : validator.validate(resource, after)) {
                    if (!existing.contains(key(resource, diagnostic))) {
                        add(found, seen, causeOf(diagnostic, changedBy), diagnostic, resource);
                    }
                }
            }
        }
        return new Outcome(found, validated, impacted.size());
    }

    // ------------------------------------------------------------------
    // the resource sets

    private ValidationEnvironment environment(
            ParsedContent view, ContentPack manifestSource, List<ContentResource> own,
            Set<NamespacedId> assets, Map<NamespacedId, int[]> sizes) {
        List<ContentPack> packs = new ArrayList<>();
        for (ContentPack other : view.packs()) {
            if (!other.id().equals(pack)) {
                packs.add(other);
            }
        }
        packs.add(new ContentPack(pack, packDirectory,
                manifestSource == null ? null : manifestSource.manifest(), own));
        return new ValidationEnvironment(packs, assets, sizes, staticReferences);
    }

    /** The untouched resources that name an id the list changed, directly or through another such resource. */
    private static List<ContentResource> untouched(
            ParsedContent view, List<ContentResource> ownResult, Set<ContentResource> touched, String pack) {
        List<ContentResource> all = new ArrayList<>();
        for (ContentPack other : view.packs()) {
            if (!other.id().equals(pack)) {
                all.addAll(other.resources());
            }
        }
        ownResult.stream().filter(resource -> !touched.contains(resource)).forEach(all::add);
        return all;
    }

    private static List<ContentResource> impacted(
            ParsedContent view, List<ContentResource> ownResult, Set<ContentResource> touched, String pack,
            Set<String> changed) {
        if (changed.isEmpty()) {
            return List.of();
        }
        Map<String, List<ContentResource>> mentions = new HashMap<>();
        Map<String, List<ContentResource>> byKey = new HashMap<>();
        List<List<ContentResource>> sources = new ArrayList<>();
        for (ContentPack other : view.packs()) {
            if (!other.id().equals(pack)) {
                sources.add(other.resources());
            }
        }
        sources.add(ownResult);
        for (List<ContentResource> list : sources) {
            for (ContentResource resource : list) {
                if (touched.contains(resource)) {
                    continue;
                }
                byKey.computeIfAbsent(resource.id().value(), k -> new ArrayList<>()).add(resource);
                if (resource.document().data().get(resource.id().value()) instanceof Map<?, ?> definition) {
                    Set<String> names = new HashSet<>();
                    leaves(definition, names, 0);
                    for (String name : names) {
                        mentions.computeIfAbsent(name, k -> new ArrayList<>()).add(resource);
                    }
                }
            }
        }
        Set<ContentResource> reached = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ContentResource> order = new ArrayList<>();
        Set<String> queued = new HashSet<>(changed);
        ArrayDeque<String> queue = new ArrayDeque<>(changed);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            // a resource of the same id (the block/furniture clash), and every resource that names this id
            List<ContentResource> hits = new ArrayList<>(byKey.getOrDefault(name, List.of()));
            hits.addAll(mentions.getOrDefault(name, List.of()));
            for (ContentResource resource : hits) {
                if (reached.add(resource)) {
                    order.add(resource);
                    if (queued.add(resource.id().value())) {
                        queue.add(resource.id().value());
                    }
                }
            }
        }
        return order;
    }

    /** Every string in a definition, reduced to the part after the last colon (a reference's id, bare or qualified). */
    private static void leaves(Object node, Set<String> out, int depth) {
        if (depth > 32) {
            return;
        }
        if (node instanceof String text) {
            out.add(text.substring(text.lastIndexOf(':') + 1));
        } else if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> leaves(value, out, depth + 1));
        } else if (node instanceof Iterable<?> list) {
            list.forEach(value -> leaves(value, out, depth + 1));
        }
    }

    // ------------------------------------------------------------------
    // reporting

    private static void add(
            List<OperationDiagnostic> found, Set<String> seen, int operation, Diagnostic diagnostic,
            ContentResource resource) {
        if (seen.add(key(resource, diagnostic))) {
            found.add(new OperationDiagnostic(operation, diagnostic));
        }
    }

    private static void addPreexisting(
            List<OperationDiagnostic> found, Set<String> seen, int operation, Diagnostic diagnostic,
            ContentResource resource) {
        if (seen.add(key(resource, diagnostic))) {
            found.add(new OperationDiagnostic(operation, new Diagnostic(
                    diagnostic.code(), dev.materione.api.diagnostics.Severity.WARN, diagnostic.message(),
                    diagnostic.resource(), diagnostic.location(), diagnostic.hint(), diagnostic.target()), true));
        }
    }

    private static String key(ContentResource resource, Diagnostic diagnostic) {
        return resource.id() + "|" + diagnostic.code().code() + "|" + diagnostic.message() + "|" + diagnostic.target();
    }

    /** The operation that changed the id a new finding names; the first changed id when none is named. */
    static int causeOf(Diagnostic diagnostic, Map<String, Integer> changedBy) {
        String message = diagnostic.message();
        for (Map.Entry<String, Integer> entry : changedBy.entrySet()) {
            if (message.contains("'" + entry.getKey() + "'") || message.contains(":" + entry.getKey() + "'")
                    || message.contains("'" + entry.getKey() + ":")) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, Integer> entry : changedBy.entrySet()) {
            if (message.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return changedBy.values().stream().findFirst().orElse(-1);
    }

    // ------------------------------------------------------------------
    // parsing, as the parse stage does it

    /** The resources a staged text declares, by {@link PackResources#declared}: the rule the parse stage applies. */
    private List<ContentResource> resourcesOf(String path, String text) {
        int slash = path.indexOf('/');
        if (slash < 0 || !PackLayout.CONTENT_DIRS.contains(path.substring(0, slash))) {
            return List.of();
        }
        return PackResources.declared(pack, path.substring(0, slash), packDirectory.resolve(path),
                LosslessYaml.parse(text)).resources();
    }
}
