package dev.materione.modern;

import dev.materione.api.NamespacedId;
import dev.materione.content.Templates;
import dev.materione.content.paintings.PaintingDefinition;
import dev.materione.content.paintings.PaintingType;
import dev.materione.content.sounds.SoundDefinition;
import dev.materione.content.sounds.SoundType;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.document.LosslessYamlDocument;
import dev.materione.core.pipeline.PackManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bootstrap-time read of the registry-relevant content: painting variants
 * and jukebox songs, parsed straight from the pack sources under
 * {@code <data>/content/packs/} — the same sources, parsers and template
 * pool the pipeline uses ({@link PaintingType#parse}/{@link
 * SoundType#parse} are pure map-in/definition-out), so what registers at
 * boot is exactly what a reload would compile.
 *
 * <p>Fail-soft (D6): a pack without a valid {@code pack.yml} is skipped
 * (the compiler skips it too); an unreadable or invalid definition logs a
 * warning and never blocks the rest. There is no diagnostics bus or
 * quarantine tracker at bootstrap time — the plugin's own boot reporting
 * takes over once it enables.
 */
public final class BootstrapContentReader {

    /** The registry-relevant slice of the packs in scope. */
    public record RegistryContent(List<PaintingDefinition> paintings, List<SoundDefinition> songs) {

        public RegistryContent {
            paintings = List.copyOf(paintings);
            songs = List.copyOf(songs);
        }
    }

    private BootstrapContentReader() {}

    /**
     * Reads every pack under {@code <dataDir>/content/packs/} — the layout
     * {@code StorageService.init} computes ({@code content/packs} under the
     * plugin data directory); kept as a literal derivation here because the
     * bootstrap must not run storage initialization.
     */
    public static RegistryContent read(Path dataDir, Consumer<String> warnings) {
        Path packsRoot = dataDir.resolve("content").resolve("packs");
        if (!Files.isDirectory(packsRoot)) {
            return new RegistryContent(List.of(), List.of());
        }
        List<Path> packDirs;
        try (var packs = Files.list(packsRoot)) {
            packDirs = packs.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            warnings.accept("could not list packs under " + packsRoot + ": " + e.getMessage());
            return new RegistryContent(List.of(), List.of());
        }
        var paintings = new ArrayList<PaintingDefinition>();
        var songs = new ArrayList<SoundDefinition>();
        for (Path packDir : packDirs) {
            if (!Files.isRegularFile(packDir.resolve("pack.yml"))) {
                continue; // the compiler skips manifest-less packs too
            }
            String packId = packDir.getFileName().toString();
            if (!validManifest(packDir, packId, warnings)) {
                continue;
            }
            var templates = new LinkedHashMap<String, Map<String, Object>>();
            collectDir(packDir, "templates", packId, templates, warnings);
            paintings.addAll(
                    parseDir(packDir, "paintings", packId, templates, new PaintingType(),
                            PaintingDefinition.class, warnings));
            for (SoundDefinition sound : parseDir(packDir, "sounds", packId, templates,
                    new SoundType(), SoundDefinition.class, warnings)) {
                if (sound.isSong()) {
                    songs.add(sound);
                }
            }
        }
        return new RegistryContent(paintings, songs);
    }

    private static boolean validManifest(Path packDir, String packId, Consumer<String> warnings) {
        try {
            PackManifest manifest = PackManifest.fromDocument(
                    LosslessYaml.parse(Files.readString(packDir.resolve("pack.yml"))));
            if (manifest != null) {
                return true;
            }
        } catch (IOException | RuntimeException e) {
            warnings.accept("pack " + packId + ": pack.yml unreadable (" + e.getMessage() + ")");
            return false;
        }
        warnings.accept("pack " + packId + ": pack.yml is missing 'id' — pack skipped");
        return false;
    }

    /** Mirrors {@code PackCompiler}'s template pool: {@code <pack>:<key>} → raw mapping. */
    private static void collectDir(
            Path packDir, String dir, String packId,
            Map<String, Map<String, Object>> templates, Consumer<String> warnings) {
        Path dirPath = packDir.resolve(dir);
        if (!Files.isDirectory(dirPath)) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : documents(dirPath, warnings)) {
            try {
                templates.put(NamespacedId.of(packId, entry.getKey()).toString(), entry.getValue());
            } catch (IllegalArgumentException e) {
                warnings.accept("template key '" + entry.getKey() + "' in " + packId
                        + " is not a valid id: " + e.getMessage());
            }
        }
    }

    private static <T> List<T> parseDir(
            Path packDir, String dir, String packId, Map<String, Map<String, Object>> templates,
            dev.materione.core.content.ContentType type, Class<T> resultType,
            Consumer<String> warnings) {
        Path dirPath = packDir.resolve(dir);
        if (!Files.isDirectory(dirPath)) {
            return List.of();
        }
        var out = new ArrayList<T>();
        for (Map.Entry<String, Map<String, Object>> entry : documents(dirPath, warnings)) {
            NamespacedId id;
            try {
                id = NamespacedId.of(packId, entry.getKey());
            } catch (IllegalArgumentException e) {
                warnings.accept("resource key '" + entry.getKey() + "' in " + packId
                        + " is not a valid id: " + e.getMessage());
                continue;
            }
            try {
                Object parsed = type.parse(id, entry.getValue(), templates).orElse(null);
                if (parsed == null) {
                    warnings.accept("resource " + id + " failed to parse as " + type.name());
                    continue;
                }
                if (resultType.isInstance(parsed)) {
                    out.add(resultType.cast(parsed));
                }
            } catch (RuntimeException e) {
                warnings.accept("resource " + id + " crashed at bootstrap: " + e);
            }
        }
        return out;
    }

    /**
     * One yml file, many keyed resources (§7.2): every top-level mapping
     * entry of every document is one resource. Unreadable files and parse
     * errors warn and are skipped — the same resources fail visibly in the
     * pipeline once the plugin is up.
     */
    private static List<Map.Entry<String, Map<String, Object>>> documents(
            Path dirPath, Consumer<String> warnings) {
        var out = new ArrayList<Map.Entry<String, Map<String, Object>>>();
        List<Path> yamls;
        try (var files = Files.list(dirPath)) {
            yamls = files.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            warnings.accept("could not list " + dirPath + ": " + e.getMessage());
            return out;
        }
        for (Path file : yamls) {
            LosslessYamlDocument document;
            try {
                document = LosslessYaml.parse(Files.readString(file));
            } catch (IOException | RuntimeException e) {
                warnings.accept("could not read " + file.getFileName() + ": " + e.getMessage());
                continue;
            }
            if (document.parseError().isPresent()) {
                warnings.accept("YAML parse error in " + file.getFileName() + ": "
                        + document.parseError().orElseThrow());
                continue;
            }
            for (var entry : document.data().entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> mapping) {
                    @SuppressWarnings("unchecked")
                    var raw = (Map<String, Object>) mapping;
                    out.add(Map.entry(entry.getKey(), raw));
                }
            }
        }
        return out;
    }
}
