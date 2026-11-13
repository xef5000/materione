package dev.materione.core.pipeline;

import dev.materione.api.pipeline.ReloadScope;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.SourceLocation;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.LosslessYamlDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PARSE (plugin architecture §4.2): walks the packs in scope, parses every
 * {@code .yml} file losslessly (§5.6) and produces content resources.
 * Fail-soft (D6): a parse failure in one file is quarantined with file,
 * line and column — it never reads or blocks other files.
 *
 * <p>Targeted reloads: a full ({@code Everything}) run parses every pack;
 * {@code OnePack}/{@code FileSet} scopes splice onto the previous run's packs: untouched packs
 * are reused, and each touched pack is rebuilt whole (unchanged documents come from the hash cache,
 * so the rebuild reads but does not re-parse them). A targeted scope expands to a
 * full run whenever there is no previous run to build on — or when any
 * out-of-scope source drifted from its recorded fingerprint (the cache is
 * a correctness net first, a speed feature second).
 */
public final class ParseStage {

    /** Session-scoped document reuse: unchanged files skip read+parse entirely. */
    private final java.util.concurrent.ConcurrentHashMap<Path, CachedDocument> documentCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedDocument(String sha256, LosslessYamlDocument document) {}

    /**
     * MTR-0056 findings per pack, from the last build of that pack. Kept beside the pack so a
     * targeted run that rides an untouched pack over still reports its duplicates.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, List<Diagnostic>> duplicateFindings =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Parses per the context's scope. Never throws.
     */
    public void run(PipelineContext context) {
        try {
            parse(context);
        } finally {
            publishParseFailures(context);
            publishDuplicateFindings(context);
        }
    }

    /** MTR-0056 for every pack in this run, including packs a targeted run rode over. */
    private void publishDuplicateFindings(PipelineContext context) {
        for (ContentPack pack : context.packs()) {
            for (Diagnostic finding : duplicateFindings.getOrDefault(pack.id(), List.of())) {
                context.diagnostics().publish(finding);
            }
        }
    }

    private void parse(PipelineContext context) {
        Path root = context.packsRoot();
        ReloadScope scope = context.scope();
        java.util.List<Path> packDirs = List.of();
        if (Files.isDirectory(root)) {
            try (var packs = Files.list(root)) {
                packDirs = packs.filter(Files::isDirectory)
                        .filter(dir -> PackLayout.isDiscoveredDirectory(dir.getFileName().toString()))
                        .sorted().toList();
            } catch (IOException e) {
                context.diagnostics().publish(new Diagnostic(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                        "could not list packs under " + root + ": " + e.getMessage(),
                        null, null, "check permissions on the content directory"));
                if (context.builtinPacks().packs().isEmpty()) {
                    return;
                }
            }
        } else if (context.builtinPacks().packs().isEmpty()) {
            return;
        }
        // a folder named like a built-in pack never reaches the pipeline: the built-in pack cannot be overridden
        packDirs = withoutBuiltinIds(context, packDirs);

        if (!(scope instanceof ReloadScope.OnePack one) && !(scope instanceof ReloadScope.FileSet set)) {
            addBuiltinPacks(context);
            for (Path packDir : packDirs) {
                context.packs().add(buildPack(context, packDir));
            }
            pruneFingerprints(context, root, packDirs);
            return;
        }
        parseTargeted(context, root, scope instanceof ReloadScope.OnePack p ? p : null,
                scope instanceof ReloadScope.FileSet f ? f : null, packDirs);
        pruneFingerprints(context, root, packDirs);
    }

    /** Untouched packs retain their cached parse failures across targeted runs. */
    private void publishParseFailures(PipelineContext context) {
        Path root = context.packsRoot().toAbsolutePath().normalize();
        var failures = documentCache.entrySet().stream()
                .filter(entry -> entry.getValue().document().parseError().isPresent())
                .sorted(java.util.Map.Entry.comparingByKey())
                .toList();
        for (var entry : failures) {
            Path file = entry.getKey();
            var error = entry.getValue().document().parseError();
            BuiltinPacks.Pack shipped = builtinPackOf(context, file);
            if (shipped == null
                    && (!file.toAbsolutePath().normalize().startsWith(root) || !Files.isRegularFile(file))) {
                continue;
            }
            Diagnostic failure = new Diagnostic(
                    DiagnosticCodes.CONTENT_PARSE_FAILED, Severity.ERROR,
                    "YAML parse error: " + error.orElseThrow(),
                    null, new SourceLocation(file.toString(), 1, 1),
                    "fix the syntax error, then /materione reload");
            context.diagnostics().publish(failure);
            context.quarantine().add(
                    NamespacedId.of(shipped != null ? shipped.id() : firstSegment(root, file),
                            stripExtension(file.getFileName().toString())),
                    failure);
        }
    }

    /** The built-in pack a file is read from, or null for a file under the packs root. */
    private static BuiltinPacks.Pack builtinPackOf(PipelineContext context, Path file) {
        for (BuiltinPacks.Pack pack : context.builtinPacks().packs()) {
            if (file.getFileSystem().equals(pack.directory().getFileSystem())
                    && file.toAbsolutePath().normalize().startsWith(pack.directory().toAbsolutePath().normalize())) {
                return pack;
            }
        }
        return null;
    }

    /** Drops fingerprints of files that left the tree, so deletions can't wedge targeted scopes. */
    private void pruneFingerprints(PipelineContext context, Path root, java.util.List<Path> packDirs) {
        var kept = new java.util.HashSet<String>();
        for (Path packDir : packDirs) {
            for (Path file : PackLayout.sourceDocuments(packDir, failure -> { })) {
                kept.add(relativeOf(root, file));
            }
        }
        FingerprintCache fingerprints = context.fingerprints();
        if (fingerprints != null && !kept.isEmpty()) {
            fingerprints.retainAll(kept);
        }
    }

    /** OnePack/FileSet handling with the drift-based expansion guard. */
    private void parseTargeted(
            PipelineContext context, Path root, ReloadScope.OnePack one,
            ReloadScope.FileSet fileSet, java.util.List<Path> packDirs) {
        var inScope = new java.util.LinkedHashSet<String>();
        var touchedPacks = new java.util.LinkedHashSet<String>();
        if (one != null) {
            touchedPacks.add(one.packId());
            Path packDir = root.resolve(one.packId());
            for (Path file : PackLayout.sourceDocuments(packDir, failure -> { })) {
                inScope.add(relativeOf(root, file));
            }
        } else {
            for (Path file : fileSet.files()) {
                Path normalized = file.toAbsolutePath().normalize();
                if (!normalized.startsWith(root.toAbsolutePath()) || !Files.isRegularFile(normalized)) {
                    continue;
                }
                inScope.add(relativeOf(root, normalized));
                touchedPacks.add(firstSegment(root, normalized));
            }
        }

        // the built-in packs are not on disk under the root: a scope can never touch them
        touchedPacks.removeAll(context.builtinPacks().ids());

        String expandReason = null;
        if (context.previousPacks().isEmpty()) {
            expandReason = "no previous run to build on";
        } else if (!previousIds(context.previousPacks()).containsAll(context.builtinPacks().ids())) {
            expandReason = "a built-in pack is not in the previous run";
        } else if (one != null && previousPack(context.previousPacks(), one.packId()) == null) {
            expandReason = "pack '" + one.packId() + "' is new since the last full run";
        } else {
            try {
                if (context.fingerprints() != null
                        && context.fingerprints().driftedOutside(inScope, root)) {
                    expandReason = "out-of-scope source files changed";
                }
            } catch (IOException e) {
                expandReason = "packs root could not be scanned for drift";
            }
        }
        if (expandReason != null) {
            context.diagnostics().publish(new Diagnostic(
                    dev.materione.api.diagnostics.DiagnosticCodes.SCOPE_EXPANDED, Severity.INFO,
                    "targeted scope expanded to everything: " + expandReason,
                    null, null, null));
            addBuiltinPacks(context);
            for (Path packDir : packDirs) {
                context.packs().add(buildPack(context, packDir));
            }
            return;
        }

        // splice: untouched packs ride over; touched packs are rebuilt whole. Their documents are
        // cached by content hash, so the rebuild is cheap, and a partial rebuild would drop the
        // unchanged siblings and get duplicate winners wrong (#194).
        var previousById = new java.util.LinkedHashMap<String, ContentPack>();
        for (ContentPack pack : context.previousPacks()) {
            previousById.put(pack.id(), pack);
        }
        for (var entry : previousById.entrySet()) {
            if (!touchedPacks.contains(entry.getKey())) {
                context.packs().add(entry.getValue());
            }
        }
        for (String packId : touchedPacks.stream().sorted().toList()) {
            Path packDir = root.resolve(packId);
            if (Files.isDirectory(packDir)) {
                context.packs().add(buildPack(context, packDir));
            }
        }
    }

    private static ContentPack previousPack(java.util.List<ContentPack> packs, String id) {
        return packs.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    private static String relativeOf(Path root, Path file) {
        return root.toAbsolutePath()
                .relativize(file.toAbsolutePath()).toString().replace('\\', '/');
    }

    private static String firstSegment(Path root, Path file) {
        Path relative = root.toAbsolutePath().relativize(file.toAbsolutePath());
        return relative.getNameCount() > 1 ? relative.getName(0).toString() : "";
    }

    /** The packs the plugin ships, parsed first and always whole: they are not on disk, so no scope reaches them. */
    private void addBuiltinPacks(PipelineContext context) {
        for (BuiltinPacks.Pack pack : context.builtinPacks().packs()) {
            context.packs().add(buildPack(context, pack.id(), pack.directory(), true));
        }
    }

    /** Drops the folders that take a built-in pack's id and reports each one (MTR-0095). */
    private static List<Path> withoutBuiltinIds(PipelineContext context, List<Path> packDirs) {
        var reserved = context.builtinPacks().ids();
        if (reserved.isEmpty()) {
            return packDirs;
        }
        var kept = new ArrayList<Path>();
        for (Path packDir : packDirs) {
            String name = packDir.getFileName().toString();
            if (!reserved.contains(name)) {
                kept.add(packDir);
                continue;
            }
            context.diagnostics().publish(new Diagnostic(
                    DiagnosticCodes.BUILTIN_PACK_ID_RESERVED, Severity.ERROR,
                    "the folder '" + name + "' takes the id of a built-in pack and is ignored; the built-in pack "
                            + "ships inside the plugin and cannot be overridden",
                    null, new SourceLocation(packDir.toString(), 1, 1),
                    "rename or remove content/packs/" + name + ", then /materione reload"));
        }
        return kept;
    }

    private static java.util.Set<String> previousIds(List<ContentPack> packs) {
        var ids = new java.util.HashSet<String>();
        packs.forEach(pack -> ids.add(pack.id()));
        return ids;
    }

    private ContentPack buildPack(PipelineContext context, Path packDir) {
        return buildPack(context, packDir.getFileName().toString(), packDir, false);
    }

    private ContentPack buildPack(PipelineContext context, String packId, Path packDir, boolean builtin) {
        Path manifestFile = packDir.resolve(PackLayout.MANIFEST_FILE);
        PackManifest manifest = null;
        if (Files.isRegularFile(manifestFile)) {
            manifest = PackManifest.fromDocument(LosslessYaml.parse(readFile(manifestFile)));
            if (manifest == null) {
                context.diagnostics().publish(new Diagnostic(
                        DiagnosticCodes.MANIFEST_INVALID, Severity.ERROR,
                        "pack.yml is missing 'id' or has an invalid structure",
                        null, new SourceLocation(manifestFile.toString(), 1, 1),
                        "every pack needs pack.yml with at least 'id: <pack-id>'"));
            }
        } else {
            context.diagnostics().publish(new Diagnostic(
                    DiagnosticCodes.MANIFEST_INVALID, Severity.ERROR,
                    "no pack.yml in " + packId,
                    null, null, "create pack.yml with at least 'id: <pack-id>'"));
        }
        // every document of the pack is parsed and every surviving resource is kept: a pack built
        // here is exactly what a full run builds for it, whatever the reload scope was
        var resources = new ArrayList<ContentResource>();
        for (String dir : PackLayout.CONTENT_DIRS) {
            parseDir(context, packDir, packId, dir, resources);
        }
        DuplicateResources.Resolution duplicates = DuplicateResources.resolve(packDir, resources);
        if (duplicates.diagnostics().isEmpty()) {
            duplicateFindings.remove(packId);
        } else {
            duplicateFindings.put(packId, duplicates.diagnostics());
        }
        indexAssets(context, packDir, packId);
        return new ContentPack(packId, packDir, manifest, duplicates.kept(), builtin);
    }

    /**
     * The document for one file, reusing the cached parse when the file's
     * content hash is unchanged (session-scoped), and recording its
     * fingerprint so later targeted scopes can trust out-of-scope state.
     */
    private LosslessYamlDocument documentOf(PipelineContext context, Path file) {
        String sha256 = FingerprintCache.sha256(file);
        FingerprintCache fingerprints = context.fingerprints();
        // the shipped packs are not under the packs root and never drift: no fingerprint, no scope expansion
        if (fingerprints != null && !context.builtinPacks().contains(file)) {
            fingerprints.record(relativeOf(context.packsRoot(), file), sha256);
        }
        CachedDocument cached = documentCache.get(file);
        if (cached != null && cached.sha256().equals(sha256)) {
            return cached.document();
        }
        LosslessYamlDocument document = LosslessYaml.parse(readFile(file));
        documentCache.put(file, new CachedDocument(sha256, document));
        return document;
    }

    /** Records assets (textures, models, ...) for pure reference validation (R6). */
    private void indexAssets(PipelineContext context, Path packDir, String packId) {
        try {
            PackAssets.index(packDir, packId, context.knownAssets(), context.paintingSizes());
        } catch (IOException e) {
            context.diagnostics().publish(new Diagnostic(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                    "could not index assets of " + packId + ": " + e.getMessage(),
                    null, null, "check file permissions under assets/"));
        }
    }

    private void parseDir(
            PipelineContext context, Path packDir, String packId, String dir, List<ContentResource> out) {
        Path dirPath = packDir.resolve(dir);
        // recursive, sorted discovery (plugin.md §4.2); an unlistable subfolder is
        // reported and skipped, its siblings still parse
        var yamls = PackLayout.contentDocuments(packDir, dir, failure -> context.diagnostics().publish(
                new Diagnostic(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                        "could not list " + dirPath + ": " + failure.getMessage(),
                        null, null, "check file permissions")));
        for (Path file : yamls) {
            var document = documentOf(context, file);
            // one file, one content type, many keyed resources (§7.2): the one rule, shared with the dry run
            var declared = PackResources.declared(packId, dir, file, document);
            out.addAll(declared.resources());
            for (String key : declared.malformedKeys()) {
                context.diagnostics().publish(new Diagnostic(
                        DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                        "'" + key + "' in " + file.getFileName() + " is not a valid resource id and is skipped",
                        null, new SourceLocation(file.toString(), 1, 1),
                        "use lowercase letters, digits and _ - . / in a resource key"));
            }
        }
    }

    private static String readFile(Path file) {
        return PackResources.readOrEmpty(file);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
