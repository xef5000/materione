package dev.materione.core.pipeline.validate;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.graph.ContentEdgeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Level-3 semantic validation: reference resolution with edit-distance
 * "did you mean" suggestions (plugin architecture §4.3). A reference is a
 * string field the content type declares as a link ({@code template:},
 * {@code model:}, {@code texture:}); unresolvable references quarantine the
 * referencing resource only — never its file, never its pack.
 */
public final class ReferenceValidator implements ValidateStage.Validator {

    /** Fields treated as references at the structural floor (slice 1). */
    public static final List<String> REFERENCE_FIELDS = List.of("template", "model", "texture");

    @Override
    public List<Diagnostic> validate(ContentResource resource, ValidationEnvironment environment) {
        var problems = new ArrayList<Diagnostic>();
        Object mapping = resource.document().data().get(resource.id().value());
        if (!(mapping instanceof java.util.Map<?, ?> definition)) {
            problems.add(new Diagnostic(
                    DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                    "resource key '" + resource.id().value() + "' is not a mapping",
                    resource.id(), null,
                    "wrap the definition: '" + resource.id().value() + ":' followed by indented fields"));
            return problems;
        }
        if ("recipe".equals(resource.contentType())) {
            return validateRecipe(resource, definition, environment, problems);
        }
        for (String field : REFERENCE_FIELDS) {
            Object value = definition.get(field);
            if (!(value instanceof String target) || target.isBlank()) {
                continue;
            }
            NamespacedId targetId = resolve(resource.id().namespace(), target);
            if (!exists(environment, targetId)) {
                problems.add(new Diagnostic(
                        DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                        "unresolved " + field + " '" + target + "'",
                        resource.id(), null,
                        "did you mean '" + closest(environment, target, field) + "'?"));
            }
        }
        if ("block".equals(resource.contentType())) {
            validateDrops(resource, definition, environment, problems);
            validatePlacedBy(resource, definition, environment, problems);
            validatePlacementIdClash(resource, "furniture", environment, problems);
        }
        if ("furniture".equals(resource.contentType())) {
            validatePlacedBy(resource, definition, environment, problems);
            validateEvolution(resource, definition, environment, problems);
            validatePlacementIdClash(resource, "block", environment, problems);
        }
        if ("glyph".equals(resource.contentType())) {
            validateSourceList(resource, definition, "frames", environment, problems);
            validateGlyphAlias(resource, definition, environment, problems);
        }
        if ("sound".equals(resource.contentType())) {
            validateSourceList(resource, definition, "sounds", environment, problems);
        }
        if ("painting".equals(resource.contentType())) {
            validatePaintingSize(resource, definition, environment, problems);
        }
        if ("item".equals(resource.contentType())) {
            validateJukeboxSong(resource, definition, environment, problems);
        }
        return problems;
    }

    /**
     * A painting's texture must measure exactly its block dimensions in
     * pixels (16 per block) — the client crops or tiles otherwise, and the
     * wrong size is invisible until someone hangs it (MTR-0041).
     */
    private static void validatePaintingSize(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        String texture = definition.get("texture") instanceof String text ? text : null;
        if (texture == null || texture.isBlank()) {
            return; // missing texture already diagnosed as unresolved reference
        }
        int width = definition.get("width") instanceof Number w ? w.intValue() : 1;
        int height = definition.get("height") instanceof Number h ? h.intValue() : 1;
        String cleaned = texture.replace('\\', '/');
        if (cleaned.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        // mirror PaintingType.resolveTexture: bare names gain the painting/ prefix
        int colon = cleaned.indexOf(':');
        String path = colon >= 0 ? cleaned.substring(colon + 1) : cleaned;
        if (!path.startsWith("painting/")) {
            path = "painting/" + path;
        }
        NamespacedId assetId = resolve(resource.id().namespace(),
                colon >= 0 ? cleaned.substring(0, colon + 1) + path : path);
        int[] size = environment.paintingSizes().get(assetId);
        if (size == null) {
            return; // not a readable painting png: nothing to compare
        }
        if (size[0] != width * 16 || size[1] != height * 16) {
            problems.add(new Diagnostic(
                    DiagnosticCodes.PAINTING_SIZE_MISMATCH, Severity.ERROR,
                    "painting " + resource.id() + " texture is " + size[0] + "×" + size[1]
                            + " px but its " + width + "×" + height + " blocks need "
                            + (width * 16) + "×" + (height * 16) + " px",
                    resource.id(), null,
                    "resize the texture (16 px per block) or set width/height to match the pixels"));
        }
    }

    /**
     * An item's {@code jukebox_song:} must name a sound definition that
     * declares a {@code jukebox:} section — a plain sound plays nowhere.
     */
    private static void validateJukeboxSong(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(definition.get("jukebox_song") instanceof String target) || target.isBlank()) {
            return;
        }
        NamespacedId soundId = resolve(resource.id().namespace(), target);
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(soundId.namespace())) {
                continue;
            }
            for (ContentResource candidate : pack.resources()) {
                if ("sound".equals(candidate.contentType()) && candidate.id().equals(soundId)) {
                    if (candidate.document().data().get(candidate.id().value()) instanceof Map<?, ?> sound
                            && sound.get("jukebox") instanceof Map<?, ?>) {
                        return;
                    }
                    problems.add(new Diagnostic(
                            DiagnosticCodes.JUKEBOX_SONG_INVALID, Severity.ERROR,
                            "item " + resource.id() + ": jukebox song '" + target
                                    + "' is a sound without a jukebox section",
                            resource.id(), null,
                            "add a jukebox: section (description, duration, comparator_output) to the sound"));
                    return;
                }
            }
        }
        problems.add(new Diagnostic(
                DiagnosticCodes.JUKEBOX_SONG_INVALID, Severity.ERROR,
                "item " + resource.id() + ": jukebox song '" + target + "' is not a sound definition",
                resource.id(), null,
                "the reference must name a sound in the sounds/ directory"));
    }

    /**
     * Media source lists ({@code sounds:}, glyph {@code frames:}) must point
     * at shipped pack assets; a missing source breaks the definition's whole
     * output, so it quarantines only that definition (MTR-0039/0040).
     */
    private static void validateSourceList(
            ContentResource resource,
            Map<?, ?> definition,
            String field,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(definition.get(field) instanceof List<?> sources)) {
            return;
        }
        for (Object entry : sources) {
            if (!(entry instanceof String source) || source.isBlank()) {
                continue;
            }
            String cleaned = source.replace('\\', '/');
            if (cleaned.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) {
                cleaned = cleaned.substring(0, cleaned.length() - 4);
            }
            NamespacedId assetId = resolve(resource.id().namespace(), cleaned);
            if (!assetId.isReservedNamespace() && !environment.knownAssets().contains(assetId)) {
                problems.add(new Diagnostic(
                        assetDiagnostic(field),
                        Severity.ERROR,
                        resource.id() + ": " + field + " source '" + source + "' is not a shipped asset",
                        resource.id(), null,
                        "upload the file under the pack's assets/ (sounds/ for sound sources, "
                                + "textures/ for glyph frames)"));
            }
        }
    }

    private static dev.materione.api.diagnostics.DiagnosticCode assetDiagnostic(String field) {
        return "frames".equals(field)
                ? DiagnosticCodes.GLYPH_TEXTURE_MISSING
                : DiagnosticCodes.SOUND_SOURCE_MISSING;
    }

    /**
     * A glyph {@code reference:} must name another glyph — and not another
     * alias: aliases resolve exactly one level at text time, so an alias
     * targeting an alias renders literal forever with no other signal.
     */
    private static void validateGlyphAlias(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(definition.get("reference") instanceof String target) || target.isBlank()) {
            return;
        }
        NamespacedId targetId = resolve(resource.id().namespace(), target);
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(targetId.namespace())) {
                continue;
            }
            for (ContentResource candidate : pack.resources()) {
                if ("glyph".equals(candidate.contentType()) && candidate.id().equals(targetId)) {
                    if (candidate.document().data().get(candidate.id().value())
                            instanceof Map<?, ?> targetDef && targetDef.get("reference") != null) {
                        problems.add(new Diagnostic(
                                DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                                "glyph " + resource.id() + " references alias '" + target
                                        + "' — aliases resolve one level only",
                                resource.id(), null,
                                "point the reference at a glyph that owns bitmaps "
                                        + "(texture, frames or rows/columns sheet)"));
                    }
                    return;
                }
            }
        }
        problems.add(new Diagnostic(
                DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                "glyph " + resource.id() + " references unknown glyph '" + target + "'",
                resource.id(), null,
                "the referenced id must be another glyph in any of the loaded packs"));
    }

    /**
     * A furniture's evolution {@code target} must resolve to another
     * furniture resource; a bad target quarantines the furniture only.
     */
    private static void validateEvolution(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(definition.get("evolution") instanceof List<?> stages)) {
            return;
        }
        for (Object stage : stages) {
            if (!(stage instanceof Map<?, ?> mapping)
                    || !(mapping.get("target") instanceof String target) || target.isBlank()) {
                continue;
            }
            NamespacedId targetId = resolve(resource.id().namespace(), target);
            if (!isFurniture(environment, targetId)) {
                problems.add(new Diagnostic(
                        DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                        "furniture " + resource.id() + ": evolution target '" + target
                                + "' is not a furniture",
                        resource.id(), null,
                        "evolution targets must reference other materione furniture"));
            }
        }
    }

    /** True when {@code id} names a furniture resource. */
    private static boolean isFurniture(ValidationEnvironment environment, NamespacedId id) {
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(id.namespace())) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if ("furniture".equals(resource.contentType()) && resource.id().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A block's {@code item:} placement link must resolve to an item
     * resource — a bad link quarantines the block only.
     */
    private static void validatePlacedBy(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(definition.get("item") instanceof String target) || target.isBlank()) {
            return;
        }
        NamespacedId targetId = resolve(resource.id().namespace(), target);
        if (!isItem(environment, targetId)) {
            problems.add(new Diagnostic(
                    DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                    "block " + resource.id() + ": item '" + target + "' is not an item",
                    resource.id(), null,
                    "the item: field names the materione item that places this block"));
        }
    }

    /**
     * Block drops: every entry of {@code drops:} must resolve to an item
     * resource, a vanilla {@code minecraft:} id, or another block/furniture
     * of the pack — placeable definitions carry an auto placement item, so
     * a block can drop itself (or any sibling) by id. A bad entry
     * quarantines the block only.
     */
    private static void validateDrops(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(definition.get("drops") instanceof List<?> drops)) {
            return;
        }
        for (Object entry : drops) {
            if (!(entry instanceof String target) || target.isBlank()) {
                continue;
            }
            NamespacedId targetId = resolve(resource.id().namespace(), target);
            if (!isItem(environment, targetId) && !isPlaceable(environment, targetId)) {
                problems.add(new Diagnostic(
                        DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                        "block " + resource.id() + ": drop '" + target + "' is not an item",
                        resource.id(), null,
                        "drops must reference materione items, placeable blocks/furniture, "
                                + "or vanilla minecraft: ids"));
            }
        }
    }

    /**
     * A block and a furniture sharing one id is ambiguous: both claim the
     * same auto placement item, and the furniture's interact handler wins
     * the click. Warned, never quarantined — the content still loads.
     */
    private static void validatePlacementIdClash(
            ContentResource resource,
            String otherType,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(resource.id().namespace())) {
                continue;
            }
            for (ContentResource other : pack.resources()) {
                if (otherType.equals(other.contentType()) && other.id().equals(resource.id())) {
                    problems.add(new Diagnostic(
                            DiagnosticCodes.AMBIGUOUS_PLACEMENT_ID, Severity.WARN,
                            resource.contentType() + " " + resource.id()
                                    + " shares its id with a " + otherType,
                            resource.id(), null,
                            "both claim the same auto placement item; give one a different id "
                                    + "or link it explicitly with 'item:'"));
                    return;
                }
            }
        }
    }

    /**
     * Recipes: result + every ingredient must resolve to an item (or a
     * vanilla {@code minecraft:} id). One bad reference quarantines the
     * recipe only — never its neighbors (runtime-systems §9).
     */
    private static List<Diagnostic> validateRecipe(
            ContentResource resource,
            Map<?, ?> definition,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        checkRecipeReference(resource, "result", definition.get("result"), environment, problems);
        if (definition.get("input") != null) {
            checkRecipeReference(resource, "input", definition.get("input"), environment, problems);
        }
        if (definition.get("ingredients") instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                checkRecipeReference(resource, "ingredient", entry.getValue(), environment, problems);
            }
        } else if (definition.get("ingredients") instanceof List<?> list) {
            for (Object entry : list) {
                checkRecipeReference(resource, "ingredient", entry, environment, problems);
            }
        }
        return problems;
    }

    private static void checkRecipeReference(
            ContentResource resource,
            String field,
            Object value,
            ValidationEnvironment environment,
            List<Diagnostic> problems) {
        if (!(value instanceof String target) || target.isBlank()) {
            return;
        }
        NamespacedId targetId = resolve(resource.id().namespace(), target);
        if (!exists(environment, targetId) && !isItem(environment, targetId)) {
            problems.add(new Diagnostic(
                    DiagnosticCodes.UNRESOLVED_REFERENCE, Severity.ERROR,
                    "recipe " + resource.id() + ": unresolved " + field + " '" + target + "'",
                    resource.id(), null,
                    "recipe references must be materione items or vanilla minecraft: ids"));
        }
    }

    /** True when {@code id} names an item resource (recipe endpoints). */
    private static boolean isItem(ValidationEnvironment environment, NamespacedId id) {
        if (id.isReservedNamespace()) {
            return true; // vanilla item id — legality checked at apply by the platform
        }
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(id.namespace())) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if ("item".equals(resource.contentType()) && resource.id().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when {@code id} names a block or furniture (they carry auto placement items). */
    private static boolean isPlaceable(ValidationEnvironment environment, NamespacedId id) {
        if (id.isReservedNamespace()) {
            return false;
        }
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(id.namespace())) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if (("block".equals(resource.contentType()) || "furniture".equals(resource.contentType()))
                        && resource.id().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static NamespacedId resolve(String defaultNamespace, String raw) {
        return raw.contains(":")
                ? NamespacedId.parse(raw)
                : NamespacedId.of(defaultNamespace, raw);
    }

public static ContentEdgeType edgeTypeFor(String field) {
        return switch (field) {
            case "template" -> ContentEdgeType.TEMPLATE;
            case "model" -> ContentEdgeType.ITEM_MODEL;
            case "texture" -> ContentEdgeType.TEXTURE;
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static boolean exists(ValidationEnvironment environment, NamespacedId id) {
        if (id.isReservedNamespace()) {
            return true; // minecraft: references are vanilla by definition
        }
        for (ContentPack pack : environment.packs()) {
            if (!pack.id().equals(id.namespace())) {
                continue;
            }
            for (ContentResource resource : pack.resources()) {
                if (resource.id().equals(id)) {
                    return true;
                }
            }
        }
        return environment.knownAssets().contains(id);
    }

    private static String closest(ValidationEnvironment environment, String target, String field) {
        String best = target;
        int bestDistance = Integer.MAX_VALUE;
        for (ContentPack pack : environment.packs()) {
            for (ContentResource resource : pack.resources()) {
                if (field.equals("template") != "template".equals(resource.contentType())) {
                    continue;
                }
                int distance = levenshtein(target, resource.id().value());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = resource.id().toString();
                }
            }
        }
        return best;
    }

    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
