package dev.materione.core.pipeline;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.SourceLocation;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.document.LosslessYaml;
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
 */
public final class ParseStage {

    /**
     * Parses every pack under the context's packs root (scope filtering
     * arrives with targeted reload in slice 3; a full run parses everything
     * in scope already). Never throws.
     */
    public void run(PipelineContext context) {
        Path root = context.packsRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var packs = Files.list(root)) {
            var packDirs = packs.filter(Files::isDirectory).sorted().toList();
            for (Path packDir : packDirs) {
                parsePack(context, packDir);
            }
        } catch (IOException e) {
            context.diagnostics().publish(new Diagnostic(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                    "could not list packs under " + root + ": " + e.getMessage(),
                    null, null, "check permissions on the content directory"));
        }
    }

    private void parsePack(PipelineContext context, Path packDir) {
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
        var resources = new ArrayList<ContentResource>();
        for (String dir : PackLayout.CONTENT_DIRS) {
            parseDir(context, packDir, packId, dir, resources);
        }
        indexAssets(context, packDir, packId);
        context.packs().add(new ContentPack(packId, packDir, manifest, resources));
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
                if (dot > 0) {
                    relative = relative.substring(0, dot);
                }
                try {
                    context.knownAssets().add(NamespacedId.of(packId, relative));
                } catch (IllegalArgumentException ignored) {
                    // asset names outside the id grammar surface as unresolved
                    // references at validation, not as index failures here
                }
            });
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
        if (!Files.isDirectory(dirPath)) {
            return;
        }
        String contentType = dir.endsWith("templates") ? "template" : dir.substring(0, dir.length() - 1);
        try (var files = Files.list(dirPath)) {
            var yamls = files.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
            for (Path file : yamls) {
                var document = LosslessYaml.parse(readFile(file));
                if (document.parseError().isPresent()) {
                    Diagnostic parseFailure = new Diagnostic(
                            DiagnosticCodes.CONTENT_PARSE_FAILED, Severity.ERROR,
                            "YAML parse error: " + document.parseError().orElseThrow(),
                            null, new SourceLocation(file.toString(), 1, 1),
                            "fix the syntax error, then /materione reload");
                    context.diagnostics().publish(parseFailure);
                    context.quarantine().add(
                            NamespacedId.of(packId, stripExtension(file.getFileName().toString())),
                            parseFailure);
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
        } catch (IOException e) {
            context.diagnostics().publish(new Diagnostic(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                    "could not list " + dirPath + ": " + e.getMessage(),
                    null, null, "check file permissions"));
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
