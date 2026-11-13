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
import java.util.Set;

/**
 * Master section 5.4 level 3 over a transaction's <em>result</em>: {@link ReferenceValidator}, the one reference
 * validator the pipeline runs (D6), over the parsed pack set with the staged documents laid over the one pack the
 * list writes. Nothing is written; the pack's stored documents are read through the store and everything else is the
 * last pipeline run's parse ({@link ParsedContent}), so a reference into another pack resolves against that pack's
 * stored state (the one-pack rule limits writes, not reads).
 *
 * <h2>Scope, and why it is complete</h2>
 * A resource's validation result is a function of its own definition and of what its reference strings name (the
 * resources and assets they resolve to, and, for a {@code template:} chain, the definitions along the chain). So it
 * can change in the transaction only when
 * <ol>
 *   <li>the resource is in a document the list touched (its definition is new), or
 *   <li>a resource a touched document declared, before or after the list, is one it names (directly, or through
 *       another resource of this set: a template chain, a block's item).
 * </ol>
 * Set 1 is validated in full. Set 2 is found by a name index over the untouched resources, followed transitively,
 * and validated before and after: only the findings the list <em>adds</em> are reported, so a resource that was
 * already broken is not charged to a list that did not break it. Everything else is, by the argument above,
 * unchanged and is not read again. The cost is one linear pass over the untouched definitions' strings, not a
 * validation of the pack.
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
            String text = java.nio.file.Files.readString(file);
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

    StagedReferences(String pack, Path packDirectory, ParsedContent parsed, DocumentCache cache) {
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
                    LosslessYamlDocument document = entry.document();
                    if (document.parseError().isPresent()) {
                        continue;
                    }
                    String path = packDirectory.relativize(file).toString().replace('\\', '/');
                    for (String key : document.data().keySet()) {
                        ContentResource resource = resourceOf(directory, path, key, document);
                        if (resource != null) {
                            stored.add(resource);
                            if (!byPath.containsKey(path)) {
                                result.add(resource);
                            }
                        }
                    }
                } catch (IOException unreadable) {
                    // the parse stage reads an unreadable file as empty: it declares nothing
                }
            }
        }
        cache.replace(pack, walked);
    }

    Outcome check(List<StagedDocument> staged) throws IOException {
        Map<String, StagedDocument> byPath = new LinkedHashMap<>();
        staged.forEach(document -> byPath.put(document.path(), document));

        // the pack as stored, and as the list leaves it
        List<ContentResource> stored = new ArrayList<>();
        List<ContentResource> result = new ArrayList<>();
        Map<ContentResource, Integer> touchedOperation = new IdentityHashMap<>();
        Set<ContentResource> touchedResources = Collections.newSetFromMap(new IdentityHashMap<>());
        readStored(byPath, stored, result);
        for (StagedDocument document : staged) {
            if (document.text() == null) {
                continue;
            }
            for (ContentResource resource : resourcesOf(document.path(), document.text())) {
                result.add(resource);
                if (document.touched()) {
                    touchedResources.add(resource);
                    touchedOperation.put(resource, document.operation());
                }
            }
        }

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

        // 1. the resources the list wrote
        int validated = 0;
        for (ContentResource resource : result) {
            if (touchedResources.contains(resource)) {
                validated++;
                int operation = touchedOperation.get(resource);
                for (Diagnostic diagnostic : validator.validate(resource, after)) {
                    add(found, seen, operation, diagnostic, resource);
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
        List<ContentResource> impacted = impacted(view, result, touchedResources, pack, changedBy.keySet());
        if (!impacted.isEmpty()) {
            ValidationEnvironment before = environment(view, manifestSource, stored, assets, sizes);
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
        return new ValidationEnvironment(packs, assets, sizes);
    }

    /** The untouched resources that name an id the list changed, directly or through another such resource. */
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

    private static String key(ContentResource resource, Diagnostic diagnostic) {
        return resource.id() + "|" + diagnostic.code().code() + "|" + diagnostic.message() + "|" + diagnostic.target();
    }

    /** The operation that changed the id a new finding names; the first changed id when none is named. */
    private static int causeOf(Diagnostic diagnostic, Map<String, Integer> changedBy) {
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

    private List<ContentResource> resourcesOf(String path, String text) {
        LosslessYamlDocument document = LosslessYaml.parse(text);
        List<ContentResource> out = new ArrayList<>();
        if (document.parseError().isPresent() || !path.contains("/")) {
            return out;
        }
        String directory = path.substring(0, path.indexOf('/'));
        for (String key : document.data().keySet()) {
            ContentResource resource = resourceOf(directory, path, key, document);
            if (resource != null) {
                out.add(resource);
            }
        }
        return out;
    }

    /** One keyed resource of a document; null when the key is not a mapping, not a valid id, or not in a content directory. */
    private ContentResource resourceOf(String directory, String path, String key, LosslessYamlDocument document) {
        if (!PackLayout.CONTENT_DIRS.contains(directory) || !(document.data().get(key) instanceof Map<?, ?>)) {
            return null;
        }
        String type = "currencies".equals(directory) ? "currency" : directory.substring(0, directory.length() - 1);
        try {
            return new ContentResource(NamespacedId.of(pack, key), type, packDirectory.resolve(path), document);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
