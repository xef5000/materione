package dev.materione.content.glyphs;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.CompileRejectException;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.text.GlyphCodepoints;
import dev.materione.content.Templates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The glyph content type (M4, runtime-systems §6): parses {@code glyphs/*.yml}
 * (template-aware) and compiles each definition into bitmap font providers
 * through the emitter. Providers from every glyph of one font key merge into
 * a single {@code font/<name>.json} that the compiler wires into the
 * client's default font via {@code type:reference} providers; the shift
 * font lives once ({@code materione:shift}) and is referenced everywhere.
 *
 * <p>Shapes: a basic glyph is one texture on one codepoint; {@code rows}/
 * {@code columns} slice a sprite sheet (one chars-string per row, codepoints
 * row-major); {@code frames} animates one codepoint per texture; {@code
 * reference} aliases another glyph's cells without allocating anything.
 */
public final class GlyphType implements ContentType {

    /** The allocation kind backing glyph codepoints (durable, never reused). */
    public static final String ALLOCATION_KIND = "glyph-codepoint";

    private static final int MAX_SHEET_CELLS = 4096;
    private static final int MAX_FRAMES = 1024;

    @Override
    public String name() {
        return "glyph";
    }

    @Override
    public String directory() {
        return "glyphs";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Glyph",
                "A bitmap glyph — emoji, icon, sprite-sheet slice or animation — rendered through the pack's fonts",
                List.of(
                        new ParameterSchema("texture", ParameterType.REFERENCE, true, null,
                                "Texture under the pack's assets (textures/ implied)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Texture", "appearance", 10, false, "texture", "texture", "gui/heart")),
                        new ParameterSchema("font", ParameterType.STRING, false, null,
                                "Custom font hosting this glyph; unset = the pack's glyph font, referenced from minecraft:default so every text context renders it", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Font", "appearance", 20, true, "text", "", "")),
                        new ParameterSchema("ascent", ParameterType.INTEGER, false, "8",
                                "Baseline offset in pixels (distance from the text baseline up to the texture's top)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Ascent", "appearance", 30, false, "integer", "", "8")),
                        new ParameterSchema("height", ParameterType.INTEGER, false, "8",
                                "Rendered height in pixels (width scales proportionally)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Height", "appearance", 40, false, "integer", "", "8")),
                        new ParameterSchema("rows", ParameterType.INTEGER, false, "1",
                                "Sprite-sheet rows (each row gets its own chars string)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Sheet rows", "appearance", 50, true, "integer", "", "1")),
                        new ParameterSchema("columns", ParameterType.INTEGER, false, "1",
                                "Sprite-sheet columns", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Sheet columns", "appearance", 60, true, "integer", "", "1")),
                        new ParameterSchema("frames", ParameterType.LIST, false, null,
                                "Animation frames: one texture per frame, one codepoint each", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Frames", "appearance", 45, true, "list", "", "gui/heart_frame_1")),
                        new ParameterSchema("reference", ParameterType.REFERENCE, false, null,
                                "Alias another glyph's bitmaps (no codepoint of its own)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Reference glyph", "appearance", 70, true, "reference", "glyph", "")),
                        new ParameterSchema("index", ParameterType.STRING, false, null,
                                "Cells of the referenced glyph to use (e.g. 2 or 1..4)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Cells", "appearance", 80, true, "text", "", "1")),
                        new ParameterSchema("placeholders", ParameterType.LIST, false, null,
                                "Chat placeholders that produce this glyph (e.g. :heart:)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Placeholders", "behavior", 10, false, "list", "", ":heart:")),
                        new ParameterSchema("permission", ParameterType.STRING, false, null,
                                "Permission required to use this glyph in chat", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Permission", "behavior", 20, true, "text", "", "")),
                        new ParameterSchema("emoji", ParameterType.BOOLEAN, false, "false",
                                "Participates in emoji mode (chat interception + tab-completion)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Emoji", "behavior", 30, false, "boolean", "", "true")),
                        new ParameterSchema("tab_complete", ParameterType.BOOLEAN, false, "false",
                                "Offer placeholders as chat completions", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Chat completion", "behavior", 40, true, "boolean", "", "true")),
                        new ParameterSchema("shadow_color", ParameterType.STRING, false, null,
                                "Shadow color applied when rendered (#RRGGBB or #AARRGGBB)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata("Shadow color", "appearance", 90, true, "text", "", "#40000000"))),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty();
        }
        String texture = Templates.text(merged, "texture");
        String reference = Templates.text(merged, "reference");
        List<String> frames = stringList(merged.get("frames"));
        if (texture == null && reference == null && frames.isEmpty()) {
            return Optional.empty(); // a glyph needs a texture, frames, or an alias target
        }
        if (texture != null && reference != null) {
            return Optional.empty(); // an alias borrows, it does not own bitmaps
        }
        if (reference != null && !frames.isEmpty()) {
            return Optional.empty();
        }
        int ascent = intOf(merged, "ascent", 8);
        int height = intOf(merged, "height", 8);
        int rows = intOf(merged, "rows", 1);
        int columns = intOf(merged, "columns", 1);
        if (height < 1 || height > 512 || ascent < -128 || ascent > 512) {
            return Optional.empty();
        }
        if (rows < 1 || rows > 64 || columns < 1 || columns > 64) {
            return Optional.empty();
        }
        if (frames.size() > MAX_FRAMES || (long) rows * columns > MAX_SHEET_CELLS) {
            return Optional.empty();
        }
        return Optional.of(new GlyphDefinition(
                id,
                fontKey(id.namespace(), textOrDefault(merged, "font", "default")),
                resolveTexture(id.namespace(), texture),
                frames.stream().map(frame -> resolveTexture(id.namespace(), frame)).toList(),
                ascent,
                height,
                rows,
                columns,
                reference == null ? null : Templates.shortRef(id.namespace(), reference),
                indexSpec(merged),
                stringList(merged.get("placeholders")),
                Templates.text(merged, "permission"),
                boolOf(merged, "emoji", false),
                boolOf(merged, "tab_complete", false),
                Templates.text(merged, "shadow_color")));
    }

    @Override
    public void compile(
            Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        if (!(definition instanceof GlyphDefinition glyph)) {
            return;
        }
        if (glyph.isReference()) {
            return; // aliases resolve at text-pipeline time; no pack output
        }
        // the default font key is null: those glyphs share the pack's glyph font
        String fontKey = glyph.fontKey() != null
                ? glyph.fontKey()
                : glyph.id().namespace() + ":glyphs";
        int count = glyph.codepointCount();
        dev.materione.api.content.AllocatedRange run =
                allocations.range(glyph.id(), ALLOCATION_KIND, count);
        int first = GlyphCodepoints.of(run.start());
        if (first + count - 1 >= GlyphCodepoints.SHIFT_BLOCK_START) {
            throw new CompileRejectException(
                    DiagnosticCodes.GLYPH_CODEPOINT_EXHAUSTED,
                    "glyph " + glyph.id() + " needs codepoints at or above the shift font's block (U+E101)",
                    "the private-use range before U+E101 is full; retire unused glyphs "
                            + "(the allocation table never reuses codepoints) or contact the maintainers");
        }
        if (glyph.isAnimated()) {
            for (int frame = 0; frame < glyph.frames().size(); frame++) {
                emitter.fontProvider(fontKey, provider(
                        glyph.frames().get(frame),
                        glyph.ascent(),
                        glyph.height(),
                        List.of(charString(first + frame))));
            }
            return;
        }
        // sheet: one chars-string per row, codepoints row-major
        var chars = new ArrayList<String>(glyph.rows());
        for (int row = 0; row < glyph.rows(); row++) {
            var line = new StringBuilder(glyph.columns());
            for (int column = 0; column < glyph.columns(); column++) {
                line.append((char) (first + row * glyph.columns() + column));
            }
            chars.add(line.toString());
        }
        emitter.fontProvider(fontKey, provider(
                glyph.texture(), glyph.ascent(), glyph.height(), chars));
    }

    // --- helpers -------------------------------------------------------------

    private static Map<String, Object> provider(
            String file, int ascent, int height, List<String> chars) {
        var out = new LinkedHashMap<String, Object>();
        out.put("type", "bitmap");
        // 1.21.2+ font format: "file" is a real texture path and needs the
        // extension (authoring YAML stays extension-less)
        out.put("file", file.endsWith(".png") ? file : file + ".png");
        out.put("ascent", ascent);
        out.put("height", height);
        out.put("chars", chars);
        return out;
    }

    private static String charString(int codepoint) {
        return String.valueOf((char) codepoint);
    }

    /**
     * Full font key: bare names resolve against the glyph's own pack. The
     * default font yields {@code null} — those glyphs live in the pack's
     * glyph font ({@code <namespace>:glyphs}) and are referenced from
     * {@code minecraft:default}, so runtime text stays unstyled.
     */
    static String fontKey(String namespace, String font) {
        if (font.equals("default")) {
            return null;
        }
        return font.contains(":") ? font : namespace + ":" + font;
    }

    /** Texture refs are resource locations under the pack's textures/ (font JSON convention). */
    static String resolveTexture(String namespace, String texture) {
        if (texture == null) {
            return null;
        }
        return texture.contains(":") ? texture : namespace + ":" + texture;
    }

    private static int intOf(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /**
     * The authored reference cell spec: YAML authors write a single cell as a
     * number ({@code index: 2}) as naturally as a range string ({@code index:
     * "1..4"}) — both must reach the runtime spec, a dropped number would
     * silently render cell 1 forever.
     */
    static String indexSpec(Map<String, Object> map) {
        Object value = map.get("index");
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return value instanceof Number number ? number.toString() : null;
    }

    private static boolean boolOf(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String textOrDefault(Map<String, Object> map, String key, String fallback) {
        String value = Templates.text(map, key);
        return value != null ? value : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (Object entry : list) {
            if (entry instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}
