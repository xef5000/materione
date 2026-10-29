package dev.materione.content.glyphs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.AllocatedRange;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.content.ResourcePresentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Glyph type behavior: shapes, codepoint runs, font provider emission. */
class GlyphTypeTest {

    private final GlyphType type = new GlyphType();

    @Test
    void parsesBasicGlyphWithResolvedRefs() {
        var parsed = type.parse(
                NamespacedId.parse("sample:heart"),
                map("texture", "gui/heart", "placeholders", List.of(":heart:", "<3"), "emoji", true),
                Map.of());
        assertThat(parsed).isPresent();
        var glyph = (GlyphDefinition) parsed.get();
        assertThat(glyph.texture()).isEqualTo("sample:gui/heart");
        // the default font yields a null key: runtime text stays unstyled,
        // compile emits into <pack>:glyphs which minecraft:default references
        assertThat(glyph.fontKey()).isNull();
        assertThat(glyph.ascent()).isEqualTo(8);
        assertThat(glyph.height()).isEqualTo(8);
        assertThat(glyph.codepointCount()).isEqualTo(1);
        assertThat(glyph.placeholders()).containsExactly(":heart:", "<3");
        assertThat(glyph.emoji()).isTrue();
    }

    @Test
    void presentationExposesBitmapSourcesAndTypedAliasTargets() {
        var basic = parseGlyph(map("texture", "gui/heart"));
        assertThat(type.presentation(basic)).contains(
                new ResourcePresentation(null, null, "sample:gui/heart.png", null));

        var sheet = parseGlyph(map("texture", "gui/sheet", "rows", 2, "columns", 3));
        assertThat(type.presentation(sheet).orElseThrow().texture())
                .isEqualTo("sample:gui/sheet.png");

        var animated = parseGlyph(map("frames", List.of("gui/frame_1", "gui/frame_2")));
        assertThat(type.presentation(animated).orElseThrow().texture())
                .isEqualTo("sample:gui/frame_1.png");

        var alias = parseGlyph(map("reference", "sample:heart"));
        assertThat(type.presentation(alias).orElseThrow().reference())
                .isEqualTo(new ResourcePresentation.Reference(
                        "glyphs", NamespacedId.parse("sample:heart")));

        var explicitExtension = parseGlyph(map("texture", "gui/heart.png"));
        assertThat(type.presentation(explicitExtension).orElseThrow().texture())
                .isEqualTo("sample:gui/heart.png");
    }

    @Test
    void templateInheritanceApplies() {
        var templates = new LinkedHashMap<String, Map<String, Object>>();
        templates.put("sample:emoji_base", map("ascent", 9, "emoji", true));
        var parsed = type.parse(
                NamespacedId.parse("sample:star"),
                map("template", "emoji_base", "texture", "gui/star"),
                templates);
        var glyph = (GlyphDefinition) parsed.orElseThrow();
        assertThat(glyph.ascent()).isEqualTo(9);
        assertThat(glyph.emoji()).isTrue();
    }

    @Test
    void textureAndReferenceAreMutuallyExclusive() {
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("texture", "gui/x"), Map.of())).isPresent();
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("reference", "sample:heart"), Map.of())).isPresent();
        assertThat(type.parse(NamespacedId.parse("sample:x"), map("emoji", true), Map.of())).isEmpty();
        assertThat(type.parse(
                NamespacedId.parse("sample:x"),
                map("texture", "gui/x", "reference", "sample:heart"),
                Map.of())).isEmpty();
    }

    @Test
    void basicGlyphEmitsOneProviderOnItsAllocatedCodepoint() {
        var glyph = parseGlyph(map("texture", "gui/heart"));
        var emitter = new RecordingEmitter();
        type.compile(glyph, null, emitter, allocations(runs("1,1")));

        assertThat(emitter.fonts).hasSize(1);
        var emission = emitter.fonts.get(0);
        assertThat(emission.fontKey()).isEqualTo("sample:glyphs");
        // glyph fonts join the default font: unstyled text reaches private-use codepoints only through it (#198)
        assertThat(emission.membership()).isEqualTo(PackEmitter.FontMembership.JOINS_DEFAULT);
        assertThat(emission.provider())
                .containsEntry("type", "bitmap")
                // 1.21.2+ font format wants the extension on the texture path
                .containsEntry("file", "sample:gui/heart.png")
                .containsEntry("ascent", 8)
                .containsEntry("height", 8);
        assertThat(emission.provider().get("chars")).isEqualTo(List.of("\uA410")); // 42000
    }

    @Test
    void sheetGlyphSlicesRowsRowMajor() {
        var glyph = parseGlyph(map("texture", "gui/sheet", "rows", 2, "columns", 3));
        var emitter = new RecordingEmitter();
        type.compile(glyph, null, emitter, allocations(runs("7,6")));

        assertThat(emitter.fonts).hasSize(1);
        assertThat(glyph.codepointCount()).isEqualTo(6);
        // row-major: row 1 is 42000+6 .. +8, row 2 is +9 .. +11
        var row1 = new StringBuilder();
        var row2 = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            row1.append((char) (41999 + 7 + i));
            row2.append((char) (41999 + 10 + i));
        }
        assertThat(emission(emitter, 0).provider().get("chars"))
                .isEqualTo(List.of(row1.toString(), row2.toString()));
    }

    @Test
    void animatedGlyphEmitsOneProviderPerFrame() {
        var glyph = parseGlyph(map(
                "frames", List.of("gui/f1", "gui/f2", "gui/f3"), "ascent", 10));
        var emitter = new RecordingEmitter();
        type.compile(glyph, null, emitter, allocations(runs("1,3")));

        assertThat(glyph.isAnimated()).isTrue();
        assertThat(emitter.fonts).hasSize(3);
        for (int frame = 0; frame < 3; frame++) {
            var provider = emission(emitter, frame).provider();
            assertThat(provider).containsEntry("file", "sample:gui/f" + (frame + 1) + ".png");
            assertThat(provider).containsEntry("ascent", 10);
            assertThat((List<?>) provider.get("chars")).hasSize(1);
            assertThat((String) ((List<?>) provider.get("chars")).get(0))
                    .isEqualTo(String.valueOf((char) (42000 + frame)));
        }
    }

    @Test
    void referenceGlyphEmitsNothingAndAllocatesNothing() {
        var glyph = parseGlyph(map("reference", "sample:heart", "index", "1..3"));
        var emitter = new RecordingEmitter();
        type.compile(glyph, null, emitter, rejectingAllocations());

        assertThat(glyph.isReference()).isTrue();
        assertThat(glyph.codepointCount()).isZero();
        assertThat(emitter.fonts).isEmpty();
    }

    @Test
    void referenceIndexSpecReadsNumbersAndStrings() {
        // YAML authors write a single cell as a number (index: 2) as naturally
        // as a range string — a dropped number would silently render cell 1
        var numeric = parseGlyph(map("reference", "sample:heart", "index", 2));
        assertThat(numeric.indexSpec()).isEqualTo("2");
        var range = parseGlyph(map("reference", "sample:heart", "index", "1..4"));
        assertThat(range.indexSpec()).isEqualTo("1..4");
        var absent = parseGlyph(map("reference", "sample:heart"));
        assertThat(absent.indexSpec()).isNull();
    }

    @Test
    void explicitFontKeyIsHonored() {
        var glyph = parseGlyph(map("texture", "gui/x", "font", "fancy"));
        assertThat(glyph.fontKey()).isEqualTo("sample:fancy");
        var namespaced = parseGlyph(map("texture", "gui/x", "font", "other:default"));
        assertThat(namespaced.fontKey()).isEqualTo("other:default");
        // an explicit "default" is the same unstyled pack glyph font
        var explicitDefault = parseGlyph(map("texture", "gui/x", "font", "default"));
        assertThat(explicitDefault.fontKey()).isNull();
    }

    @Test
    void selfDescriptionCoversTheAuthoringSurface() {
        var description = type.describe();
        assertThat(description.displayName()).isEqualTo("Glyph");
        assertThat(description.parameters())
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("texture"))
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("frames"))
                .anySatisfy(p -> assertThat(p.id()).isEqualTo("placeholders"));
    }

    // --- helpers -------------------------------------------------------------

    private GlyphDefinition parseGlyph(Map<String, Object> definition) {
        return (GlyphDefinition) type.parse(
                NamespacedId.parse("sample:glyph"), definition, Map.of()).orElseThrow();
    }

    private static GlyphDefinitionEmitted emission(RecordingEmitter emitter, int index) {
        return emitter.fonts.get(index);
    }

    private record GlyphDefinitionEmitted(
            String fontKey, Map<String, Object> provider, PackEmitter.FontMembership membership) {}

    /** Fake allocation table: {@code runs} maps kind → the ranges it hands out. */
    private static dev.materione.api.content.Allocations allocations(Map<String, String> runs) {
        return new dev.materione.api.content.Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                throw new IllegalStateException("glyphs allocate via range only");
            }

            @Override
            public AllocatedRange range(NamespacedId resource, String kind, int count) {
                return AllocatedRange.parse(runs.get(kind));
            }

            @Override
            public Optional<String> lookup(NamespacedId resource, String kind) {
                return Optional.empty();
            }
        };
    }

    private static dev.materione.api.content.Allocations rejectingAllocations() {
        return new dev.materione.api.content.Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                throw new IllegalStateException("references must not allocate");
            }

            @Override
            public Optional<String> lookup(NamespacedId resource, String kind) {
                return Optional.empty();
            }
        };
    }

    private static Map<String, String> runs(String... kindToRange) {
        var out = new LinkedHashMap<String, String>();
        for (String entry : kindToRange) {
            out.put(GlyphType.ALLOCATION_KIND, entry);
        }
        return out;
    }

    private static Map<String, Object> map(Object... pairs) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static final class RecordingEmitter implements PackEmitter {
        final List<GlyphDefinitionEmitted> fonts = new ArrayList<>();

        @Override
        public void fontProvider(String fontKey, Map<String, Object> providerJson,
                PackEmitter.FontMembership membership) {
            fonts.add(new GlyphDefinitionEmitted(fontKey, providerJson, membership));
        }

        @Override
        public void itemDefinition(NamespacedId id, Map<String, Object> json) {}

        @Override
        public void model(NamespacedId id, Map<String, Object> json) {}

        @Override
        public void copyAsset(Path sourceFile, String destination) {}

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {
            throw new IllegalStateException("glyphs must not emit vanilla-namespace files");
        }

        @Override
        public void description(String text) {}
    }
}
