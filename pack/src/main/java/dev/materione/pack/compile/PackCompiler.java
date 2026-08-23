package dev.materione.pack.compile;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.core.content.ContentType;
import dev.materione.core.content.PackEmitter;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.pack.allocation.AllocationTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.serialize.minecraft.MinecraftResourcePackWriter;

/**
 * COMPILE (plugin architecture §4.4): content graph → resource pack, staged
 * deterministically under {@code generated/pack/}.
 *
 * <p>Determinism (D7): assets are collected into sorted maps, JSON is
 * emitted with sorted keys, and creative's writer produces the ZIP — the
 * same graph + allocation always yields the same bytes. The staged output's
 * SHA-256 is compared to the live pack's: equal hash → apply and delivery
 * are skipped entirely (no-op reloads are free).
 *
 * <p>Allocation is persisted <em>before</em> outputs depending on it are
 * written (§6.3): {@code flush()} runs before any staging write. Generated
 * output never overwrites hand-authored files: content-type emitters write
 * under their generated namespaces, asset copies land at their verbatim
 * source paths (§7.2).
 *
 * <p>Atlas coverage: textures a pack ships outside vanilla's {@code item}/
 * {@code block} conventions never reach the client's block atlas on their
 * own — the compiler emits one merged {@code blocks.json} covering them
 * (MTR-0017 flags what remains uncovered).
 */
public final class PackCompiler {

    private static final String DEFAULT_DESCRIPTION = "Materione";

    /** The one atlas file vanilla loads for item/block model textures. */
    private static final String ATLAS_PATH = "assets/minecraft/atlases/blocks.json";

    /** Texture dirs vanilla's block atlas already scans for every namespace. */
    private static final Set<String> VANILLA_COVERED_DIRS = Set.of("item", "block");

    /** Dirs with their own atlas or special loading path — not block-atlas content. */
    private static final Set<String> NON_BLOCK_ATLAS_DIRS = Set.of(
            "font", "particle", "environment", "colormap", "entity");

    /** One compile's outcome. */
    public record Outcome(Path stagedZip, String sha256, int itemDefinitions, int generatedModels) {}

    /** A {@link PackEmitter#vanillaFile} fragment awaiting the deterministic merge. */
    private record VanillaFragment(String path, Map<String, Object> json) {}

    /** Collects emitter output deterministically, then hands it to creative. */
    private static final class Emitter implements PackEmitter {
        final Map<String, Map<String, Object>> itemDefinitions = new TreeMap<>();
        final Map<String, Map<String, Object>> models = new TreeMap<>();
        final List<String[]> assetCopies = new ArrayList<>(); // [source, destination]
        final List<VanillaFragment> vanillaFragments = new ArrayList<>();
        /** User-shipped override files; they merge ahead of generated fragments. */
        final List<VanillaFragment> userVanillaFragments = new ArrayList<>();
        String description = DEFAULT_DESCRIPTION;
        String atlasJson;
        Map<String, Map<String, Object>> vanillaJson;

        @Override
        public void itemDefinition(NamespacedId id, Map<String, Object> itemModelJson) {
            itemDefinitions.put("assets/" + id.namespace() + "/items/" + id.value() + ".json", itemModelJson);
        }

        @Override
        public void model(NamespacedId id, Map<String, Object> modelJson) {
            models.put("assets/" + id.namespace() + "/models/" + id.value() + ".json", modelJson);
        }

        @Override
        public void copyAsset(Path sourceFile, String destination) {
            assetCopies.add(new String[] {sourceFile.toString(), destination});
        }

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {
            vanillaFragments.add(new VanillaFragment(path, json));
        }

        @Override
        public void description(String text) {
            description = text;
        }
    }

    /**
     * Compiles the packs of one pipeline run. Never throws — parse or
     * compile problems of a single resource surface as diagnostics and that
     * resource's output is skipped (fail-soft, D6); a broken or throwing
     * content type never poisons the rest of the pack.
     */
    public Outcome compile(
            List<ContentPack> packs,
            List<ContentType> contentTypes,
            AllocationTable allocation,
            Path generatedDir,
            dev.materione.api.diagnostics.DiagnosticsBus diagnostics,
            dev.materione.core.diagnostics.QuarantineTracker quarantine) {
        var byDirectory = new LinkedHashMap<String, ContentType>();
        for (ContentType type : contentTypes) {
            byDirectory.put(type.directory(), type);
        }
        var emitter = new Emitter();
        dev.materione.core.content.Allocations allocations = new dev.materione.core.content.Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                return allocation.allocate(resource, kind);
            }

            @Override
            public java.util.Optional<String> lookup(NamespacedId resource, String kind) {
                return allocation.allocationOf(resource, kind);
            }
        };

        // template pool: id → raw definition (template chains resolve at parse)
        var templates = new LinkedHashMap<String, Map<String, Object>>();
        for (ContentPack pack : packs) {
            for (ContentResource resource : pack.resources()) {
                if ("template".equals(resource.contentType())) {
                    Object definition = resource.document().data().get(resource.id().value());
                    if (definition instanceof Map<?, ?> mapping) {
                        @SuppressWarnings("unchecked")
                        var rawTemplate = (Map<String, Object>) mapping;
                        templates.put(resource.id().toString(), rawTemplate);
                    }
                }
            }
        }

        for (ContentPack pack : packs) {
            if (pack.manifest() == null) {
                continue;
            }
            copyPackAssets(emitter, pack);
            for (ContentResource resource : pack.resources()) {
                ContentType type = byDirectory.get(resource.contentType() + "s");
                if (type == null) {
                    continue;
                }
                Object definition = resource.document().data().get(resource.id().value());
                if (!(definition instanceof Map<?, ?> mapping)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var rawDefinition = (Map<String, Object>) mapping;
                Optional<Object> parsed;
                try {
                    parsed = type.parse(resource.id(), rawDefinition, templates);
                } catch (RuntimeException e) {
                    quarantineResource(resource, e, diagnostics, quarantine);
                    continue;
                }
                if (parsed.isEmpty()) {
                    diagnostics.publish(new Diagnostic(
                            DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                            "resource " + resource.id() + " failed to parse as " + type.name(),
                            resource.id(), null,
                            "check the required fields (the registry self-description documents them)"));
                    continue;
                }
                NamespacedId modelId = NamespacedId.of(
                        resource.id().namespace(),
                        resource.id().value() + "_model");
                try {
                    type.compile(parsed.get(), modelId, emitter, allocations);
                } catch (dev.materione.core.content.CompileRejectException e) {
                    Diagnostic rejected = new Diagnostic(
                            e.code(), Severity.ERROR, e.getMessage(), resource.id(), null, e.hint());
                    diagnostics.publish(rejected);
                    quarantine.add(resource.id(), rejected);
                } catch (RuntimeException e) {
                    quarantineResource(resource, e, diagnostics, quarantine);
                }
            }
        }

        buildAtlas(emitter, diagnostics);
        buildVanillaFiles(emitter, diagnostics);

        try {
            allocation.flush(); // allocation persists before dependent outputs
        } catch (IOException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                    "allocation table could not be persisted: " + e.getMessage(),
                    null, null, "check storage/ permissions; outputs were not written"));
            return null;
        }

        return writeStaged(emitter, generatedDir);
    }

    /**
     * A throwing parse/compile is contained to its resource: diagnostic
     * MTR-0011, quarantine entry, output skipped — the pack survives (D6).
     */
    private static void quarantineResource(
            ContentResource resource,
            RuntimeException e,
            dev.materione.api.diagnostics.DiagnosticsBus diagnostics,
            dev.materione.core.diagnostics.QuarantineTracker quarantine) {
        Diagnostic crash = new Diagnostic(
                DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.ERROR,
                "content type crashed on " + resource.id() + ": " + e,
                resource.id(), null,
                "this resource was skipped; if it worked before, a recent edit is the cause");
        diagnostics.publish(crash);
        quarantine.add(resource.id(), crash);
    }

    private static void copyPackAssets(Emitter emitter, ContentPack pack) {
        Path assets = pack.directory().resolve("assets");
        if (!Files.isDirectory(assets)) {
            return;
        }
        List<Path> files;
        try (var walk = Files.walk(assets)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return; // already diagnosed at parse (asset indexing)
        }
        for (Path file : files) {
            String relative = assets.relativize(file).toString().replace('\\', '/');
            emitter.copyAsset(file, "assets/" + pack.id() + "/" + relative);
        }
    }

    /**
     * Emits the block-atlas source list: every texture directory shipped
     * outside vanilla's {@code item}/{@code block} conventions gets a
     * directory source, merged into one {@code blocks.json} — without it
     * the client never stitches those textures and every model referencing
     * them renders missing. A user-shipped {@code blocks.json} is merged,
     * never clobbered; if it cannot be parsed it ships verbatim and the
     * generated sources are dropped (fail-soft, MTR-0017).
     */
    private static void buildAtlas(Emitter emitter, dev.materione.api.diagnostics.DiagnosticsBus diagnostics) {
        var dirs = new TreeSet<String>();
        var uncovered = new ArrayList<String>();
        String userAtlasSource = null;
        for (String[] copy : emitter.assetCopies) {
            String dest = copy[1];
            if (dest.equals(ATLAS_PATH)) {
                userAtlasSource = copy[0];
                continue;
            }
            if (!dest.endsWith(".png")) {
                continue;
            }
            // assets/<namespace>/textures/<rest>
            String[] segments = dest.split("/");
            if (segments.length < 4 || !"textures".equals(segments[2])) {
                continue;
            }
            String rest = dest.substring(("assets/" + segments[1] + "/textures/").length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                uncovered.add(dest); // directly under textures/ — no directory source reaches it
                continue;
            }
            String dir = rest.substring(0, slash);
            if (!VANILLA_COVERED_DIRS.contains(dir) && !NON_BLOCK_ATLAS_DIRS.contains(dir)) {
                dirs.add(dir);
            }
        }
        if (!uncovered.isEmpty()) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.TEXTURE_NOT_IN_ATLAS, Severity.WARN,
                    "texture(s) not covered by any atlas source: " + summarize(uncovered),
                    null, null,
                    "place textures under textures/item or textures/block, or ship an atlas json"));
        }
        if (dirs.isEmpty() && userAtlasSource == null) {
            return;
        }
        var sources = new ArrayList<Object>();
        var seen = new TreeSet<String>();
        if (userAtlasSource != null) {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(Files.readString(Path.of(userAtlasSource)));
            } catch (IOException | RuntimeException e) {
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.TEXTURE_NOT_IN_ATLAS, Severity.WARN,
                        "user-shipped " + ATLAS_PATH + " could not be parsed (" + e.getMessage()
                                + "); shipping it verbatim without generated sources",
                        null, null, "check the atlas json syntax"));
                return;
            }
            for (Object entry : jsonSources(parsed)) {
                seen.add(sourceKey(entry));
                sources.add(entry);
            }
        }
        for (String dir : dirs) {
            if (seen.add("directory:" + dir + ":" + dir + "/")) {
                var source = new LinkedHashMap<String, Object>();
                source.put("type", "directory");
                source.put("source", dir);
                source.put("prefix", dir + "/");
                sources.add(source);
            }
        }
        var root = new LinkedHashMap<String, Object>();
        root.put("sources", sources);
        emitter.assetCopies.removeIf(copy -> copy[1].equals(ATLAS_PATH));
        emitter.atlasJson = toJson(root);
    }

    /**
     * Merges the vanilla-namespace override fragments ({@code
     * blockstates/*.json}, {@code sounds.json}) into one deterministic JSON
     * per path, absorbing user-shipped copies of the same files instead of
     * copying them verbatim (mirrors the atlas merge). Conflicting leaf keys
     * keep the first value and are diagnosed (MTR-0020).
     */
    private static void buildVanillaFiles(Emitter emitter, dev.materione.api.diagnostics.DiagnosticsBus diagnostics) {
        absorbUserVanillaJson(emitter, diagnostics);
        if (emitter.vanillaFragments.isEmpty() && emitter.userVanillaFragments.isEmpty()) {
            return;
        }
        var merged = new TreeMap<String, Map<String, Object>>();
        for (VanillaFragment fragment : emitter.userVanillaFragments) {
            Map<String, Object> dst = merged.computeIfAbsent(fragment.path(), key -> new LinkedHashMap<>());
            deepMerge(dst, fragment.json(), "assets/minecraft/" + fragment.path(), diagnostics);
        }
        for (VanillaFragment fragment : emitter.vanillaFragments) {
            Map<String, Object> dst = merged.computeIfAbsent(fragment.path(), key -> new LinkedHashMap<>());
            deepMerge(dst, fragment.json(), "assets/minecraft/" + fragment.path(), diagnostics);
        }
        emitter.vanillaJson = merged;
    }

    /** User-shipped mergeable minecraft-ns files become override fragments; unparseable ones ship verbatim. */
    private static void absorbUserVanillaJson(Emitter emitter, dev.materione.api.diagnostics.DiagnosticsBus diagnostics) {
        var absorbed = new ArrayList<String[]>();
        for (String[] copy : emitter.assetCopies) {
            if (!isMergeableVanillaFile(copy[1])) {
                continue;
            }
            try {
                Object json = fromGson(JsonParser.parseString(Files.readString(Path.of(copy[0]))));
                if (json instanceof Map<?, ?> mapping) {
                    @SuppressWarnings("unchecked")
                    var typed = (Map<String, Object>) mapping;
                    emitter.userVanillaFragments.add(new VanillaFragment(
                            copy[1].substring("assets/minecraft/".length()), typed));
                    absorbed.add(copy);
                    continue;
                }
                diagnostics.publish(conflictDiagnostic(
                        copy[1] + " is not a JSON object; shipping it verbatim"));
            } catch (IOException | RuntimeException e) {
                diagnostics.publish(conflictDiagnostic(
                        copy[1] + " could not be parsed (" + e.getMessage() + "); shipping it verbatim"));
            }
        }
        emitter.assetCopies.removeAll(absorbed);
    }

    /** Paths whose vanilla-namespace overrides merge by key rather than replace wholesale. */
    private static boolean isMergeableVanillaFile(String destination) {
        if (!destination.endsWith(".json") || !destination.startsWith("assets/minecraft/")) {
            return false;
        }
        String rest = destination.substring("assets/minecraft/".length());
        return rest.startsWith("blockstates/") || rest.equals("sounds.json");
    }

    private static Diagnostic conflictDiagnostic(String message) {
        return new Diagnostic(
                DiagnosticCodes.VANILLA_OVERRIDE_CONFLICT, Severity.WARN, message,
                null, null, "later sources cannot redefine a key another source already claimed");
    }

    /** Recursive map merge; conflicting leaves keep the first value and warn once per key. */
    private static void deepMerge(
            Map<String, Object> dst,
            Map<String, Object> src,
            String path,
            dev.materione.api.diagnostics.DiagnosticsBus diagnostics) {
        for (var entry : src.entrySet()) {
            Object existing = dst.get(entry.getKey());
            if (existing instanceof Map<?, ?> && entry.getValue() instanceof Map<?, ?>) {
                // descend on a writable copy: fragments may carry immutable maps
                var child = new LinkedHashMap<String, Object>();
                @SuppressWarnings("unchecked")
                var typedExisting = (Map<String, Object>) existing;
                child.putAll(typedExisting);
                @SuppressWarnings("unchecked")
                var typedSrc = (Map<String, Object>) entry.getValue();
                deepMerge(child, typedSrc, path + "#" + entry.getKey(), diagnostics);
                dst.put(entry.getKey(), child);
                continue;
            }
            if (existing != null && !String.valueOf(existing).equals(String.valueOf(entry.getValue()))) {
                diagnostics.publish(conflictDiagnostic(
                        "conflicting value for '" + path + "#" + entry.getKey()
                                + "'; keeping the first ('" + existing + "')"));
                continue;
            }
            dst.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    /** The {@code sources} array of a parsed user atlas, or an empty list. */
    private static List<Object> jsonSources(JsonElement parsed) {
        var out = new ArrayList<Object>();
        if (parsed.isJsonObject() && parsed.getAsJsonObject().has("sources")
                && parsed.getAsJsonObject().get("sources").isJsonArray()) {
            for (JsonElement entry : parsed.getAsJsonObject().getAsJsonArray("sources")) {
                out.add(fromGson(entry));
            }
        }
        return out;
    }

    private static Object fromGson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            var map = new LinkedHashMap<String, Object>();
            for (var entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), fromGson(entry.getValue()));
            }
            return map;
        }
        if (element.isJsonArray()) {
            var list = new ArrayList<Object>();
            for (JsonElement entry : element.getAsJsonArray()) {
                list.add(fromGson(entry));
            }
            return list;
        }
        var primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber();
        }
        return primitive.getAsString();
    }

    private static String sourceKey(Object source) {
        if (source instanceof Map<?, ?> map) {
            return map.get("type") + ":" + map.get("source") + ":" + map.get("prefix");
        }
        return String.valueOf(source);
    }

    /** First entries of the list, with a count of the remainder. */
    private static String summarize(List<String> paths) {
        int limit = 8;
        if (paths.size() <= limit) {
            return String.join(", ", paths);
        }
        return String.join(", ", paths.subList(0, limit)) + " …(+" + (paths.size() - limit) + " more)";
    }

    private static Outcome writeStaged(Emitter emitter, Path generatedDir) {
        ResourcePack pack = ResourcePack.resourcePack();
        pack.packMeta(PackFormats.spec(), Component.text(emitter.description));
        emitter.itemDefinitions.forEach((path, json) ->
                pack.unknownFile(path, team.unnamed.creative.base.Writable.stringUtf8(toJson(json))));
        emitter.models.forEach((path, json) ->
                pack.unknownFile(path, team.unnamed.creative.base.Writable.stringUtf8(toJson(json))));
        for (String[] copy : emitter.assetCopies) {
            // verbatim asset copy: binary content as-is (textures, sounds…)
            pack.unknownFile(copy[1], team.unnamed.creative.base.Writable.path(java.nio.file.Path.of(copy[0])));
        }
        if (emitter.atlasJson != null) {
            pack.unknownFile(ATLAS_PATH, team.unnamed.creative.base.Writable.stringUtf8(emitter.atlasJson));
        }
        if (emitter.vanillaJson != null) {
            emitter.vanillaJson.forEach((path, json) -> pack.unknownFile(
                    "assets/minecraft/" + path,
                    team.unnamed.creative.base.Writable.stringUtf8(toJson(json))));
        }

        Path staging = generatedDir.resolve("pack");
        Path zip = staging.resolve("pack.zip");
        try {
            Files.createDirectories(staging);
            MinecraftResourcePackWriter.minecraft().writeToZipFile(zip, pack);
        } catch (IOException e) {
            return null;
        }
        String sha256 = sha256Of(zip);
        return new Outcome(zip, sha256, emitter.itemDefinitions.size(), emitter.models.size());
    }

    /** Deterministic JSON: sorted keys, no insignificant whitespace. */
    private static String toJson(Map<String, Object> json) {
        StringBuilder out = new StringBuilder();
        appendMap(json, out);
        return out.toString();
    }

    private static void appendMap(Map<String, Object> map, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (var entry : new TreeMap<>(map).entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(entry.getKey(), out);
            out.append(':');
            appendValue(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void appendValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            appendString(s, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            var typed = (Map<String, Object>) map;
            appendMap(typed, out);
        } else if (value instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object entry : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendValue(entry, out);
            }
            out.append(']');
        } else {
            appendString(String.valueOf(value), out);
        }
    }

    private static void appendString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String sha256Of(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }
}
