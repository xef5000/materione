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
 * {@code OnePack}/{@code FileSet} scopes parse only their targets and
 * splice onto the previous run's packs. A targeted scope expands to a
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

    private static final java.util.function.Predicate<Path> ALL_FILES = file -> true;

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
        if (!Files.isDirectory(root)) {
            return;
        }
        java.util.List<Path> packDirs;
        try (var packs = Files.list(root)) {
            packDirs = packs.filter(Files::isDirectory)
                    .filter(dir -> PackLayout.isDiscoveredDirectory(dir.getFileName().toString()))
                    .sorted().toList();
        } catch (IOException e) {
            context.diagnostics().publish(new Diagnostic(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                    "could not list packs under " + root + ": " + e.getMessage(),
                    null, null, "check permissions on the content directory"));
            return;
        }

        if (!(scope instanceof ReloadScope.OnePack one) && !(scope instanceof ReloadScope.FileSet set)) {
            for (Path packDir : packDirs) {
                context.packs().add(buildPack(context, packDir, ALL_FILES));
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
            if (!file.toAbsolutePath().normalize().startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            Diagnostic failure = new Diagnostic(
                    DiagnosticCodes.CONTENT_PARSE_FAILED, Severity.ERROR,
                    "YAML parse error: " + error.orElseThrow(),
                    null, new SourceLocation(file.toString(), 1, 1),
                    "fix the syntax error, then /materione reload");
            context.diagnostics().publish(failure);
            context.quarantine().add(
                    NamespacedId.of(firstSegment(root, file), stripExtension(file.getFileName().toString())),
                    failure);
        }
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

        String expandReason = null;
        if (context.previousPacks().isEmpty()) {
            expandReason = "no previous run to build on";
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
            for (Path packDir : packDirs) {
                context.packs().add(buildPack(context, packDir, ALL_FILES));
            }
            return;
        }

        // splice: untouched packs ride over untouched, touched ones rebuild scoped
        var previousById = new java.util.LinkedHashMap<String, ContentPack>();
        for (ContentPack pack : context.previousPacks()) {
            previousById.put(pack.id(), pack);
        }
        java.util.function.Predicate<Path> filter = one != null
                ? file -> true
                : file -> inScope.contains(relativeOf(root, file.toAbsolutePath().normalize()));
        for (var entry : previousById.entrySet()) {
            if (!touchedPacks.contains(entry.getKey())) {
                context.packs().add(entry.getValue());
            }
        }
        for (String packId : touchedPacks.stream().sorted().toList()) {
            Path packDir = root.resolve(packId);
            if (Files.isDirectory(packDir)) {
                context.packs().add(buildPack(context, packDir, filter));
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

    private ContentPack buildPack(PipelineContext context, Path packDir, java.util.function.Predicate<Path> include) {
        String packId = packDir.getFileName().toString();
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
        // every document of the pack is parsed, not only the in-scope ones: a duplicate is
        // resolved against the whole pack, so a targeted run reaches the same winner as a full run
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
        var kept = new ArrayList<ContentResource>();
        for (ContentResource resource : duplicates.kept()) {
            if (include.test(resource.sourceFile().toAbsolutePath().normalize())) {
                kept.add(resource); // out of a targeted scope's reach — rides over from the previous run
            }
        }
        indexAssets(context, packDir, packId);
        return new ContentPack(packId, packDir, manifest, kept);
    }

    /**
     * The document for one file, reusing the cached parse when the file's
     * content hash is unchanged (session-scoped), and recording its
     * fingerprint so later targeted scopes can trust out-of-scope state.
     */
    private LosslessYamlDocument documentOf(PipelineContext context, Path file) {
        String sha256 = FingerprintCache.sha256(file);
        FingerprintCache fingerprints = context.fingerprints();
        if (fingerprints != null) {
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

    /** Asset kinds whose files are referenced relative to the kind dir (vanilla convention). */
    private static final List<String> ASSET_KIND_DIRS = List.of("textures", "models", "fonts", "sounds");

    /** Records assets (textures, models, …) for pure reference validation (R6). */
    private void indexAssets(PipelineContext context, Path packDir, String packId) {
        Path assets = packDir.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return;
        }
        try (var walk = Files.walk(assets)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                Path relativePath = assets.relativize(file);
                // model/texture ids are relative to their kind directory
                // (models/ruby_sword_model.json → <pack>:ruby_sword_model)
                Path base = relativePath;
                if (relativePath.getNameCount() > 1) {
                    String first = relativePath.getName(0).toString();
                    if (ASSET_KIND_DIRS.contains(first)) {
                        base = relativePath.subpath(1, relativePath.getNameCount());
                    }
                }
                String relative = base.toString().replace('\\', '/');
                int dot = relative.lastIndexOf('.');
                String extension = dot > 0 ? relative.substring(dot + 1) : "";
                if (dot > 0) {
                    relative = relative.substring(0, dot);
                }
                try {
                    context.knownAssets().add(NamespacedId.of(packId, relative));
                } catch (IllegalArgumentException ignored) {
                    // asset names outside the id grammar surface as unresolved
                    // references at validation, not as index failures here
                }
                // painting textures carry their pixel size to the validator —
                // read here (the IO layer) so validation stays pure (R6)
                if ("png".equals(extension) && relative.startsWith("painting/")) {
                    int[] size = pngSize(file);
                    if (size != null) {
                        context.recordPaintingSize(packId + ":" + relative, size[0], size[1]);
                    }
                }
            });
        } catch (IOException e) {
            context.diagnostics().publish(new Diagnostic(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                    "could not index assets of " + packId + ": " + e.getMessage(),
                    null, null, "check file permissions under assets/"));
        }
    }

    /**
     * The pixel size of a PNG, straight from its IHDR header (bytes 16..24):
     * big-endian width then height. Null when the file is not a readable
     * PNG — validation then skips the size check rather than guessing.
     */
    private static int[] pngSize(Path file) {
        byte[] header = new byte[24];
        int read = 0;
        try (var in = Files.newInputStream(file)) {
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) {
                    return null;
                }
                read += n;
            }
        } catch (IOException e) {
            return null;
        }
        for (int i = 0; i < 8; i++) {
            if (header[i] != PNG_SIGNATURE[i]) {
                return null;
            }
        }
        int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
        return new int[] {width, height};
    }

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private void parseDir(
            PipelineContext context, Path packDir, String packId, String dir, List<ContentResource> out) {
        Path dirPath = packDir.resolve(dir);
        String contentType = dir.endsWith("templates") ? "template" : dir.substring(0, dir.length() - 1);
        // recursive, sorted discovery (plugin.md §4.2); an unlistable subfolder is
        // reported and skipped, its siblings still parse
        var yamls = PackLayout.contentDocuments(packDir, dir, failure -> context.diagnostics().publish(
                new Diagnostic(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                        "could not list " + dirPath + ": " + failure.getMessage(),
                        null, null, "check file permissions")));
        for (Path file : yamls) {
            var document = documentOf(context, file);
            if (document.parseError().isPresent()) {
                continue;
            }
            // one file, one content type, many keyed resources (§7.2)
            for (var entry : document.data().entrySet()) {
                if (!(entry.getValue() instanceof java.util.Map)) {
                    continue;
                }
                out.add(new ContentResource(
                        NamespacedId.of(packId, entry.getKey()),
                        contentType,
                        file,
                        document));
            }
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
