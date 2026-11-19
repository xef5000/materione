package dev.materione.content.glyphs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.AllocatedRange;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.PackEmitter;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden for the font providers an animated glyph emits (M6, #9). Frame cycling is delivered by the runtime
 * (per-viewer text rewriting on a surface's clock), so the pack carries every frame as its own consecutive
 * codepoint and {@code frame_ticks} never reaches the font: the golden locks that the cycle's codepoint run, texture
 * order and font membership stay exactly what the runtime's frame table assumes.
 */
class GlyphFontGoldenTest {

    private static final String GOLDEN = "glyphs/animated-cycle-font.json";

    private final GlyphType type = new GlyphType();

    @Test
    void animatedCycleFontMatchesTheGolden(@TempDir Path tempDir) throws IOException {
        var emitted = new StringBuilder("[\n");
        emit(emitted, "sample:spark", Map.of(
                "frames", List.of("gui/spark_1", "gui/spark_2", "gui/spark_3"), "frame_ticks", 2, "ascent", 9), 1);
        emit(emitted, "sample:pulse", Map.of("frames", List.of("gui/pulse_1", "gui/pulse_2")), 4);
        // a custom font key: styled text, and not joined to the default font
        emit(emitted, "sample:flame", Map.of(
                "frames", List.of("gui/flame_1", "gui/flame_2"), "font", "fancy", "frame_ticks", 7), 6);
        emitted.setLength(emitted.length() - 2);
        emitted.append("\n]\n");

        Path actual = tempDir.resolve("animated-cycle-font.json");
        Files.writeString(actual, emitted.toString(), StandardCharsets.UTF_8);
        if (GoldenFiles.regenEnabled()) {
            GoldenFiles.writeGolden(actual, GOLDEN);
        }
        GoldenFiles.assertMatchesGolden(actual, GOLDEN);
    }

    @Test
    void frameTicksIsAuthoringOnlyAndNeverChangesTheEmittedFont() {
        var fast = fontOf("sample:glyph", Map.of("frames", List.of("gui/a", "gui/b"), "frame_ticks", 1), 1);
        var slow = fontOf("sample:glyph", Map.of("frames", List.of("gui/a", "gui/b"), "frame_ticks", 600), 1);
        var unset = fontOf("sample:glyph", Map.of("frames", List.of("gui/a", "gui/b")), 1);
        assertThat(fast).isEqualTo(slow).isEqualTo(unset);
    }

    // --- helpers -------------------------------------------------------------

    private void emit(StringBuilder out, String id, Map<String, Object> definition, int firstAllocation) {
        for (String line : fontOf(id, definition, firstAllocation)) {
            out.append(line).append(",\n");
        }
    }

    /** One JSON object line per emitted provider, in emission order, with the font key and membership. */
    private List<String> fontOf(String id, Map<String, Object> definition, int firstAllocation) {
        var glyph = (GlyphDefinition) type.parse(NamespacedId.parse(id), new LinkedHashMap<>(definition), Map.of())
                .orElseThrow();
        var lines = new java.util.ArrayList<String>();
        type.compile(glyph, null, new PackEmitter() {
            @Override
            public void fontProvider(String fontKey, Map<String, Object> provider, FontMembership membership) {
                lines.add("  {\"font\":" + json(fontKey) + ",\"membership\":" + json(membership.name())
                        + ",\"provider\":" + json(provider) + "}");
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
        }, new Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                throw new IllegalStateException("glyphs allocate via range only");
            }

            @Override
            public AllocatedRange range(NamespacedId resource, String kind, int count) {
                return new AllocatedRange(firstAllocation, count);
            }

            @Override
            public Optional<String> lookup(NamespacedId resource, String kind) {
                return Optional.empty();
            }
        });
        return lines;
    }

    /** Minimal deterministic JSON: the providers hold only maps, lists, strings and integers. */
    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            var out = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                out.append(first ? "" : ",").append(json(String.valueOf(entry.getKey()))).append(':')
                        .append(json(entry.getValue()));
                first = false;
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            return "[" + String.join(",", list.stream().map(GlyphFontGoldenTest::json).toList()) + "]";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        var out = new StringBuilder("\"");
        for (char c : String.valueOf(value).toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
