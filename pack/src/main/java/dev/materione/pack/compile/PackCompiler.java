package dev.materione.pack.compile;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.DiagnosticCodes;
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
import com.google.gson.JsonObject;
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
 * own — the compiler emits one merged {@code blocks.json} of single-source
 * entries covering them (MTR-0017 flags a user atlas that cannot be
 * parsed). 26.x clients additionally bake item models from the items atlas,
 * and one model may not span two atlases — the same single-source set is
 * mirrored into {@code items.json} in the pack base (#46; the base
 * placement is the post-65 mcmeta schema's price — see
 * writeItemAtlasOverlay). Overlay entries also carry
 * {@code min_format}/{@code max_format} alongside the legacy {@code formats}
 * (patchMcmeta) so one pack.mcmeta parses on both schema generations.
 */
public final class PackCompiler {

    private static final String DEFAULT_DESCRIPTION = "Materione";

    /** The one atlas file vanilla loads for item/block model textures. */
    private static final String ATLAS_PATH = "assets/minecraft/atlases/blocks.json";

    /**
     * The items atlas — 26.x clients bake item models from it, and its
     * vanilla sources gather only item/-convention sprites (#46).
     */
    private static final String ITEMS_ATLAS_PATH = "assets/minecraft/atlases/items.json";

    /** Texture dirs vanilla's block atlas already scans for every namespace. */
    private static final Set<String> VANILLA_COVERED_DIRS = Set.of("item", "block");

    /** Dirs with their own atlas or special loading path — not block-atlas content. */
    private static final Set<String> NON_BLOCK_ATLAS_DIRS = Set.of(
            "font", "particle", "environment", "colormap", "entity", "painting");

    /** One compile's outcome. The datapack artifact is null when no content produced datapack files. */
    public record Outcome(
            Path stagedZip, String sha256, int itemDefinitions, int generatedModels,
            Path stagedDatapackZip, String datapackSha256) {}

    /**
     * Authored text → JSON text of a text component ({@link PackEmitter#textComponent}).
     * The plugin passes its text pipeline; the default keeps the stored string,
     * for compiles without one (tests, tools).
     */
    private final java.util.function.Function<String, String> textComponents;

    /** A compiler whose text components are the stored strings unchanged. */
    public PackCompiler() {
        this(stored -> new com.google.gson.JsonPrimitive(stored).toString());
    }

    /** A compiler that turns authored text into component JSON text with {@code textComponents}. */
    public PackCompiler(java.util.function.Function<String, String> textComponents) {
        this.textComponents = textComponents;
    }

    /** A {@link PackEmitter#vanillaFile} fragment awaiting the deterministic merge. */
    private record VanillaFragment(String path, Map<String, Object> json) {}

    /** Collects emitter output deterministically, then hands it to creative. */
    private static final class Emitter implements PackEmitter {
        private final java.util.function.Function<String, String> textComponents;
        final Map<String, Map<String, Object>> itemDefinitions = new TreeMap<>();
        final Map<String, Map<String, Object>> syntheticItemDefinitions = new TreeMap<>();
        final Map<String, Map<String, Object>> models = new TreeMap<>();
        /** Font key ({@code ns:name}) → providers in compile order (fonts.json §6). */
        final Map<String, List<Map<String, Object>>> fontProviders = new TreeMap<>();
        /** Namespace → event name → event json; written as one sounds.json per namespace. */
        final Map<String, Map<String, Map<String, Object>>> soundEvents = new TreeMap<>();
        /** Datapack artifact files ({@code data/...} → json) — paintings, songs, tags. */
        final Map<String, Map<String, Object>> datapackFiles = new TreeMap<>();
        /** Compiled text effects, in compile order — the shader ladder's input. */
        final List<dev.materione.api.content.EffectSpec> textEffects = new ArrayList<>();
        final List<String[]> assetCopies = new ArrayList<>(); // [source, destination]
        final List<VanillaFragment> vanillaFragments = new ArrayList<>();
        /** User-shipped override files; they merge ahead of generated fragments. */
        final List<VanillaFragment> userVanillaFragments = new ArrayList<>();
        String description = DEFAULT_DESCRIPTION;
        String atlasJson;
        String itemsAtlasJson;
        Map<String, Map<String, Object>> vanillaJson;

        Emitter(java.util.function.Function<String, String> textComponents) {
            this.textComponents = textComponents;
        }

        @Override
        public Object textComponent(String stored) {
            if (stored == null) {
                return null;
            }
            try {
                Object json = fromGson(JsonParser.parseString(textComponents.apply(stored)));
                // {"text": "x"} is the same component as "x": keep plain text a string
                if (json instanceof Map<?, ?> map && map.size() == 1 && map.get("text") instanceof String text) {
                    return text;
                }
                return json;
            } catch (RuntimeException | LinkageError e) {
                // fail-soft (D6): a text the pipeline cannot compile ships as written
                return stored;
            }
        }

        @Override
        public void itemDefinition(NamespacedId id, Map<String, Object> itemModelJson) {
            itemDefinitions.put("assets/" + id.namespace() + "/items/" + id.value() + ".json", itemModelJson);
        }

        @Override
        public void syntheticItemDefinition(NamespacedId id, Map<String, Object> itemModelJson) {
            syntheticItemDefinitions.put(
                    "assets/" + id.namespace() + "/items/" + id.value() + ".json", itemModelJson);
        }

        @Override
        public void model(NamespacedId id, Map<String, Object> modelJson) {
            models.put("assets/" + id.namespace() + "/models/" + id.value() + ".json", modelJson);
        }

        @Override
        public void fontProvider(String fontKey, Map<String, Object> providerJson) {
            fontProviders.computeIfAbsent(fontKey, key -> new ArrayList<>()).add(providerJson);
        }

        @Override
        public void soundEvent(String soundKey, Map<String, Object> eventJson) {
            int colon = soundKey.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : soundKey.substring(0, colon);
            String name = colon < 0 ? soundKey : soundKey.substring(colon + 1);
            soundEvents.computeIfAbsent(namespace, key -> new TreeMap<>()).put(name, eventJson);
        }

        @Override
        public void datapackFile(String path, Map<String, Object> json) {
            Map<String, Object> existing = datapackFiles.get(path);
            if (existing != null && path.startsWith("data/minecraft/tags/")) {
                // merge into our own copy — the stored map belongs to the
                // content type that emitted it and may be immutable
                var merged = new LinkedHashMap<>(existing);
                mergeTagValues(merged, json);
                datapackFiles.put(path, merged);
                return;
            }
            datapackFiles.putIfAbsent(path, json);
        }

        @Override
        public void textEffect(dev.materione.api.content.EffectSpec spec) {
            textEffects.add(spec);
        }

        /**
         * Tag files from many definitions merge additively: their {@code
         * values} arrays concatenate (deduplicated, first-seen order); the
         * {@code replace} flag keeps its first value.
         */
        private static void mergeTagValues(Map<String, Object> target, Map<String, Object> addition) {
            var merged = new ArrayList<Object>();
            Object current = target.get("values");
            for (Object value : current instanceof List<?> list ? list : List.of()) {
                merged.add(value);
            }
            Object extra = addition.get("values");
            for (Object value : extra instanceof List<?> list ? list : List.of()) {
                if (!merged.contains(value)) {
                    merged.add(value);
                }
            }
            target.put("values", merged);
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
     * content type never poisons the rest of the pack. {@code
     * detectedMaxFormat} is the running server's self-reported resource
     * pack format ({@code -1} when unavailable) — see {@link
     * PackFormats#spec(int)}.
     */
    public Outcome compile(
            List<ContentPack> packs,
            List<ContentType> contentTypes,
            AllocationTable allocation,
            Path generatedDir,
            dev.materione.api.diagnostics.DiagnosticsBus diagnostics,
            dev.materione.core.diagnostics.QuarantineTracker quarantine,
            int detectedMaxFormat) {
        return compile(packs, contentTypes, allocation, generatedDir, diagnostics, quarantine,
                detectedMaxFormat, null);
    }

    public Outcome compile(
            List<ContentPack> packs,
            List<ContentType> contentTypes,
            AllocationTable allocation,
            Path generatedDir,
            dev.materione.api.diagnostics.DiagnosticsBus diagnostics,
            dev.materione.core.diagnostics.QuarantineTracker quarantine,
            int detectedMaxFormat,
            PackFormats.Version detectedMaxDataFormat) {
        var byDirectory = new LinkedHashMap<String, ContentType>();
        for (ContentType type : contentTypes) {
            byDirectory.put(type.directory(), type);
        }
        var emitter = new Emitter(textComponents);
        dev.materione.api.content.Allocations allocations = new dev.materione.api.content.Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                return allocation.allocate(resource, kind);
            }

            @Override
            public dev.materione.api.content.AllocatedRange range(
                    NamespacedId resource, String kind, int count) {
                return allocation.range(resource, kind, count);
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
                } catch (dev.materione.api.content.CompileRejectException e) {
                    Diagnostic rejected = new Diagnostic(
                            e.code(), Severity.ERROR, e.getMessage(), resource.id(), null, e.hint());
                    diagnostics.publish(rejected);
                    quarantine.add(resource.id(), rejected);
                } catch (RuntimeException e) {
                    quarantineResource(resource, e, diagnostics, quarantine);
                }
            }
        }

        // auto placement items: a block/furniture's synthetic definition
        // ships only when no real item resource owns the same id — the
        // implicit same-id link rides the item's own definition instead
        for (var entry : emitter.syntheticItemDefinitions.entrySet()) {
            emitter.itemDefinitions.putIfAbsent(entry.getKey(), entry.getValue());
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

        return writeStaged(emitter, generatedDir, detectedMaxFormat, detectedMaxDataFormat, diagnostics);
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
            if (relative.endsWith(".bbmodel")) {
                // Blockbench project sources are authoring files, not client
                // assets — they stay in content/packs (the editor saves them
                // under assets/models/source/) but never ship in pack.zip
                continue;
            }
            emitter.copyAsset(file, "assets/" + pack.id() + "/" + relative);
        }
    }

    /**
     * Emits the block-atlas source list: every texture shipped outside
     * vanilla's {@code item}/{@code block} conventions (and outside the
     * special-loading directories) gets a {@code single} source naming its
     * exact sprite id — the same shape Nexo ships, and immune to directory-
     * source prefix-composition mistakes. All merged into one {@code
     * blocks.json}. A user-shipped {@code blocks.json} is merged, never
     * clobbered; if it cannot be parsed it ships verbatim and the generated
     * sources are dropped (fail-soft, MTR-0017).
     */
    private static void buildAtlas(Emitter emitter, dev.materione.api.diagnostics.DiagnosticsBus diagnostics) {
        var sprites = new TreeSet<String>();
        String userAtlasSource = null;
        boolean userItemsAtlas = false;
        for (String[] copy : emitter.assetCopies) {
            String dest = copy[1];
            if (dest.equals(ATLAS_PATH)) {
                userAtlasSource = copy[0];
                continue;
            }
            if (dest.equals(ITEMS_ATLAS_PATH)) {
                // the user routes their own items-atlas sprites — the
                // generated mirror would shadow it on 26.x overlays
                userItemsAtlas = true;
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
            String topDir = slash < 0 ? "" : rest.substring(0, slash);
            if (VANILLA_COVERED_DIRS.contains(topDir) || NON_BLOCK_ATLAS_DIRS.contains(topDir)) {
                continue;
            }
            sprites.add(segments[1] + ":" + rest.substring(0, rest.length() - ".png".length()));
        }
        // painting textures need no atlas source: vanilla's paintings atlas
        // (atlases/paintings.json) directory-scans textures/painting/ across
        // all namespaces with an empty prefix, so the sprite id is <ns>:<path>
        // — exactly the variant's asset_id (PaintingType.resolveTexture).
        if (sprites.isEmpty() && userAtlasSource == null) {
            return;
        }
        var sources = new ArrayList<Object>();
        var itemSources = new ArrayList<Object>();
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
        for (String sprite : sprites) {
            if (seen.add("single:" + sprite)) {
                var source = new LinkedHashMap<String, Object>();
                source.put("type", "single");
                source.put("resource", sprite);
                sources.add(source);
                itemSources.add(source);
            }
        }
        var root = new LinkedHashMap<String, Object>();
        root.put("sources", sources);
        emitter.assetCopies.removeIf(copy -> copy[1].equals(ATLAS_PATH));
        emitter.atlasJson = toJson(root);
        // The generated singles are mirrored into the items atlas for 26.x
        // clients (writeItemAtlasOverlay) — user-shipped blocks.json sources
        // stay blocks-scoped, and a user-shipped items.json suppresses the
        // mirror entirely.
        if (!userItemsAtlas && !itemSources.isEmpty()) {
            var itemRoot = new LinkedHashMap<String, Object>();
            itemRoot.put("sources", itemSources);
            emitter.itemsAtlasJson = toJson(itemRoot);
        }
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
        // full-map string: single sources carry their target in "resource",
        // so type/source/prefix alone would collapse distinct entries
        return String.valueOf(source);
    }


    /** Where the shared shift font lives (one copy, referenced everywhere). */
    static final String SHIFT_FONT_KEY = "materione:shift";
    static final String SHIFT_FONT_PATH = "assets/materione/font/shift.json";
    /** The only font file unstyled text renders through (client behavior). */
    static final String DEFAULT_FONT_PATH = "assets/minecraft/font/default.json";

    /**
     * Writes one {@code assets/<ns>/font/<name>.json} per font key (the pack
     * glyph font {@code <ns>:glyphs} included) and wires everything into the
     * client's default font: {@link #DEFAULT_FONT_PATH} gains one
     * {@code type:reference} provider per generated font. Unstyled text —
     * chat, signs, item names — renders only through the default font, so
     * without the references codepoints render as missing-glyph boxes. The
     * shift font's space provider exists once ({@link #SHIFT_FONT_PATH}) and
     * is referenced from the default font and every generated font —
     * references load inside any font file, so {@code <shift:N>} still
     * resolves in styled contexts. A user-shipped default font is absorbed
     * and kept ahead of ours (atlas-merge pattern).
     */
    private static void writeFonts(
            Emitter emitter, ResourcePack pack, dev.materione.api.diagnostics.DiagnosticsBus diagnostics) {
        if (emitter.fontProviders.isEmpty()) {
            return;
        }
        // the shift font exists once and is referenced everywhere (see writeFonts)
        var shiftRoot = new LinkedHashMap<String, Object>();
        shiftRoot.put("providers", List.of(shiftProvider()));
        pack.unknownFile(SHIFT_FONT_PATH,
                team.unnamed.creative.base.Writable.stringUtf8(toJson(shiftRoot)));
        var directProviders = new ArrayList<Object>(); // explicit minecraft:default glyphs
        var references = new ArrayList<Object>(); // one per generated font file, sorted
        emitter.fontProviders.forEach((fontKey, providers) -> {
            if (fontKey.equals("minecraft:default")) {
                directProviders.addAll(providers);
                return;
            }
            int colon = fontKey.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : fontKey.substring(0, colon);
            String name = colon < 0 ? fontKey : fontKey.substring(colon + 1);
            var list = new ArrayList<Object>(providers);
            list.add(shiftReference());
            var root = new LinkedHashMap<String, Object>();
            root.put("providers", list);
            pack.unknownFile(
                    "assets/" + namespace + "/font/" + name + ".json",
                    team.unnamed.creative.base.Writable.stringUtf8(toJson(root)));
            var reference = new LinkedHashMap<String, Object>();
            reference.put("type", "reference");
            reference.put("id", fontKey);
            references.add(reference);
        });
        var defaultProviders = new ArrayList<Object>();
        defaultProviders.add(shiftReference());
        defaultProviders.addAll(references);
        defaultProviders.addAll(directProviders);
        writeDefaultFont(emitter, pack, diagnostics, defaultProviders);
    }

    /** {@code {"type":"reference","id":"materione:shift"}} — the shared shift font. */
    private static Map<String, Object> shiftReference() {
        var out = new LinkedHashMap<String, Object>();
        out.put("type", "reference");
        out.put("id", SHIFT_FONT_KEY);
        return out;
    }

    /**
     * Writes {@link #DEFAULT_FONT_PATH} with the given providers, keeping
     * providers from a user-shipped copy ahead of ours; unparseable user
     * files ship verbatim with a warning (glyphs then stay unreferenced).
     */
    private static void writeDefaultFont(
            Emitter emitter,
            ResourcePack pack,
            dev.materione.api.diagnostics.DiagnosticsBus diagnostics,
            List<Object> generatedProviders) {
        String[] userCopy = null;
        for (String[] copy : emitter.assetCopies) {
            if (copy[1].equals(DEFAULT_FONT_PATH)) {
                userCopy = copy;
                break;
            }
        }
        if (userCopy == null) {
            pack.unknownFile(DEFAULT_FONT_PATH,
                    team.unnamed.creative.base.Writable.stringUtf8(toJson(rootWithProviders(generatedProviders))));
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(java.nio.file.Path.of(userCopy[0])));
        } catch (IOException | RuntimeException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.VANILLA_OVERRIDE_CONFLICT, Severity.WARN,
                    "user-shipped " + DEFAULT_FONT_PATH + " could not be parsed ("
                            + e.getMessage() + "); shipping it verbatim without generated font references",
                    null, null, "check the font json syntax"));
            return;
        }
        Object userJson = fromGson(parsed);
        if (!(userJson instanceof Map<?, ?>) || !(((Map<?, ?>) userJson).get("providers") instanceof List<?>)) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.VANILLA_OVERRIDE_CONFLICT, Severity.WARN,
                    "user-shipped " + DEFAULT_FONT_PATH + " has no providers list; "
                            + "shipping it verbatim without generated font references",
                    null, null, "font jsons need a providers array"));
            return;
        }
        @SuppressWarnings("unchecked")
        var userProviders = (List<Object>) ((Map<?, ?>) userJson).get("providers");
        var merged = new ArrayList<Object>(userProviders);
        merged.addAll(generatedProviders);
        emitter.assetCopies.remove(userCopy);
        pack.unknownFile(DEFAULT_FONT_PATH,
                team.unnamed.creative.base.Writable.stringUtf8(toJson(rootWithProviders(merged))));
    }

    private static Map<String, Object> rootWithProviders(List<Object> providers) {
        var root = new LinkedHashMap<String, Object>();
        root.put("providers", providers);
        return root;
    }

    /** The shift font: {@code type:space} provider with power-of-two advances. */
    private static Map<String, Object> shiftProvider() {
        var advances = new LinkedHashMap<String, Object>();
        dev.materione.core.text.ShiftMath.advances().forEach(
                (character, advance) -> advances.put(String.valueOf(character), advance));
        var out = new LinkedHashMap<String, Object>();
        out.put("type", "space");
        out.put("advances", advances);
        return out;
    }

    /**
     * Emits the text-effect shader ladder (runtime-systems §6): one overlay
     * per verified format rung carrying the replacement text vertex shader
     * plus the generated per-effect include, and the mcmeta overlay
     * entries + Sodium's ignored-shaders note. Never touches the base
     * layer — a client matching no rung gets a shader-free pack. Warns
     * (MTR-0044) when the running server's own client format falls outside
     * the ladder: the effects compile, but nothing animates there.
     */
    private static void writeTextEffectShaders(
            Emitter emitter, ResourcePack pack, int detectedMaxFormat,
            dev.materione.api.diagnostics.DiagnosticsBus diagnostics,
            List<Object> overlayEntries) {
        if (emitter.textEffects.isEmpty()) {
            return;
        }
        var entries = overlayEntries;
        for (TextEffectShaders.Rung rung : TextEffectShaders.RUNGS) {
            var overlay = team.unnamed.creative.overlay.Overlay.overlay(rung.overlayDir());
            overlay.unknownFile(rung.includePath(),
                    team.unnamed.creative.base.Writable.stringUtf8(
                            TextEffectShaders.include(emitter.textEffects, rung.bakeFormat())));
            String core = "assets/minecraft/shaders/core/" + rung.shaderName();
            overlay.unknownFile(core + ".vsh", team.unnamed.creative.base.Writable.stringUtf8(
                    TextEffectShaders.template(rung.variantDir(), rung.shaderName() + ".vsh")));
            if (rung.shipJson()) {
                overlay.unknownFile(core + ".json", team.unnamed.creative.base.Writable.stringUtf8(
                        TextEffectShaders.template(rung.variantDir(), rung.shaderName() + ".json")));
            }
            pack.overlay(overlay);
            entries.add(team.unnamed.creative.metadata.overlays.OverlayEntry.of(
                    team.unnamed.creative.metadata.pack.PackFormat.format(
                            team.unnamed.creative.metadata.pack.FormatVersion.of(rung.overlayMinFormat()),
                            team.unnamed.creative.metadata.pack.FormatVersion.of(rung.overlayMaxFormat())),
                    rung.overlayDir()));
        }
        pack.sodiumMeta(team.unnamed.creative.metadata.sodium.SodiumMeta.sodium(
                TextEffectShaders.overriddenShaders()));
        if (detectedMaxFormat > 0 && !TextEffectShaders.covers(detectedMaxFormat)) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.TEXT_EFFECT_UNSUPPORTED, Severity.WARN,
                    "text effects compiled, but this server's client format " + detectedMaxFormat
                            + " is outside the verified shader ladder — effects will not animate"
                            + " for its players",
                    null, null, "the shader ladder covers client formats "
                            + TextEffectShaders.formatRanges()));
        }
    }

    /**
     * Mirrors the generated atlas single-sources into {@code items.json} in
     * the pack <em>base</em>: 26.x clients bake item models from the items
     * atlas, whose vanilla sources gather only item/-convention sprites,
     * and one model may not span two atlases (the bake is rejected, #46).
     * With the mirror, a model mixing custom-dir and item/-convention
     * sprites resolves within the items atlas while block models keep the
     * base {@code blocks.json}. It shipped in a 26.x-scoped overlay until
     * the pack.mcmeta schema split made that impossible: since pack format
     * 65 an overlay entry may not carry {@code formats} when its
     * {@code min_format} is 65+, while pre-65 clients require
     * {@code formats} on every entry and one entry without it drops the
     * whole overlays section (verified against the real 1.21.4 and 26.1.2
     * parsers). A 26-only entry can therefore never serve both client
     * generations, and in the base it is simply ignored by pre-26 clients —
     * their item models bake from the blocks atlas. A user-shipped
     * items.json still suppresses the mirror (buildAtlas).
     */
    private static void writeItemAtlasOverlay(Emitter emitter, ResourcePack pack) {
        if (emitter.itemsAtlasJson == null) {
            return;
        }
        pack.unknownFile(ITEMS_ATLAS_PATH,
                team.unnamed.creative.base.Writable.stringUtf8(emitter.itemsAtlasJson));
    }

    private static Outcome writeStaged(
            Emitter emitter, Path generatedDir, int detectedMaxFormat,
            PackFormats.Version detectedMaxDataFormat,
            dev.materione.api.diagnostics.DiagnosticsBus diagnostics) {
        ResourcePack pack = ResourcePack.resourcePack();
        pack.packMeta(PackFormats.spec(detectedMaxFormat), Component.text(emitter.description));
        var overlayEntries = new ArrayList<Object>();
        writeTextEffectShaders(emitter, pack, detectedMaxFormat, diagnostics, overlayEntries);
        writeItemAtlasOverlay(emitter, pack);
        if (!overlayEntries.isEmpty()) {
            pack.overlaysMeta(team.unnamed.creative.metadata.overlays.OverlaysMeta.of(
                    overlayEntries.stream().map(e -> (team.unnamed.creative.metadata.overlays.OverlayEntry) e)
                            .toList()));
        }
        emitter.itemDefinitions.forEach((path, json) ->
                pack.unknownFile(path, team.unnamed.creative.base.Writable.stringUtf8(toJson(json))));
        emitter.models.forEach((path, json) ->
                pack.unknownFile(path, team.unnamed.creative.base.Writable.stringUtf8(toJson(json))));
        emitter.soundEvents.forEach((namespace, events) -> pack.unknownFile(
                "assets/" + namespace + "/sounds.json",
                team.unnamed.creative.base.Writable.stringUtf8(
                        toJson(new LinkedHashMap<String, Object>(events)))));
        writeFonts(emitter, pack, diagnostics);
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
            if (!overlayEntries.isEmpty()) {
                patchMcmeta(zip);
            }
        } catch (IOException e) {
            return null;
        }
        String sha256 = sha256Of(zip);
        DatapackWriter.Artifact datapack =
                DatapackWriter.write(emitter.datapackFiles, generatedDir, diagnostics, detectedMaxDataFormat);
        return new Outcome(zip, sha256, emitter.itemDefinitions.size(), emitter.models.size(),
                datapack == null ? null : datapack.zip(),
                datapack == null ? null : datapack.sha256());
    }

    /** Shared with {@link DatapackWriter}: deterministic sorted-key JSON. */
    static String toJsonPublic(Map<String, Object> json) {
        return toJson(json);
    }

    /**
     * Adds {@code min_format}/{@code max_format} to every overlay entry.
     * creative's serializer emits the legacy {@code formats}; pre-25w31a
     * clients require it, while current clients gate overlays on the new
     * fields. Shader rungs supply their overlay ranges explicitly; the
     * current parser validates that they match the legacy {@code formats},
     * while pre-25w31a parsers ignore the new fields.
     * No-op without an overlays section.
     */
    private static void patchMcmeta(Path zip) throws IOException {
        // pass one: read the serialized mcmeta, if present
        byte[] replacement = null;
        try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            for (java.util.zip.ZipEntry entry; (entry = zin.getNextEntry()) != null; ) {
                if (entry.getName().equals("pack.mcmeta")) {
                    JsonObject root = com.google.gson.JsonParser.parseReader(
                            new java.io.InputStreamReader(zin, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    replacement = withOverlayMinMax(root);
                    break;
                }
            }
        }
        if (replacement == null) {
            return;
        }
        // pass two: rewrite the zip entry-for-entry, faithful metadata except
        // the patched mcmeta — ZipOutputStream output is deterministic for a
        // given input, unlike an in-place fs rewrite (the golden pins bytes)
        Path patched = zip.resolveSibling("pack.patched.zip");
        try (var in = new java.util.zip.ZipInputStream(Files.newInputStream(zip));
             var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(patched))) {
            for (java.util.zip.ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                java.util.zip.ZipEntry copy = new java.util.zip.ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                copy.setMethod(entry.getMethod());
                if (entry.getName().equals("pack.mcmeta")) {
                    out.putNextEntry(copy);
                    out.write(replacement);
                } else {
                    if (copy.getMethod() == java.util.zip.ZipEntry.STORED) {
                        copy.setSize(entry.getSize());
                        copy.setCompressedSize(entry.getCompressedSize());
                        copy.setCrc(entry.getCrc());
                    }
                    out.putNextEntry(copy);
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
        Files.move(patched, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * The mcmeta transform: overlay entries gain {@code min_format}/
     * {@code max_format} mirrored from their {@code formats} pair — the new
     * schema's gating fields, which pre-25w31a clients ignore. No-op without
     * an overlays section.
     */
    private static byte[] withOverlayMinMax(JsonObject root) {
        var overlays = root.getAsJsonObject("overlays");
        if (overlays != null && overlays.has("entries")) {
            for (var element : overlays.getAsJsonArray("entries")) {
                JsonObject entry = element.getAsJsonObject();
                if (!entry.has("formats")) {
                    continue;
                }
                String directory = entry.has("directory")
                        ? entry.get("directory").getAsString()
                        : null;
                TextEffectShaders.Rung rung = directory == null
                        ? null
                        : TextEffectShaders.rungForOverlay(directory);
                if (rung != null) {
                    entry.addProperty("min_format", rung.overlayMinFormat());
                    entry.addProperty("max_format", rung.overlayMaxFormat());
                    continue;
                }
                if (entry.has("min_format")) {
                    continue;
                }
                var formats = entry.get("formats");
                if (formats.isJsonArray() && formats.getAsJsonArray().size() == 2) {
                    entry.add("min_format", formats.getAsJsonArray().get(0));
                    entry.add("max_format", formats.getAsJsonArray().get(1));
                }
            }
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
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
