package dev.materione.platform.text;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeGlyphs;
import java.util.LinkedHashMap;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** The frame-cycling provider: animated glyphs in resolved text show the clock's frame (M6, #9). */
class GlyphFrameTextTest {

    private static final char F1 = 42000;
    private static final char F2 = 42001;
    private static final char F3 = 42002;

    private static RuntimeGlyphs registry() {
        var entries = new LinkedHashMap<NamespacedId, RuntimeGlyphs.Entry>();
        entries.put(NamespacedId.parse("sample:spark"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:spark"), null, new int[] {F1, F2, F3},
                List.of(), null, false, false, null, null, null, 4));
        entries.put(NamespacedId.parse("sample:heart"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:heart"), null, new int[] {42010},
                List.of(), null, false, false, null, null, null));
        // an alias pinned to the spark's second frame must not animate
        entries.put(NamespacedId.parse("sample:pinned"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:pinned"), null, new int[0],
                List.of(), null, false, false, null, "sample:spark", "2"));
        return new RuntimeGlyphs(entries);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void textResolvesToTheStillFirstFrameAndTheClockAdvancesIt() {
        RuntimeGlyphs glyphs = registry();
        Component lore = GlyphText.resolve("hit <glyph:spark> now", glyphs, GlyphText.Permissions.ALLOW_ALL);
        assertThat(plain(lore)).isEqualTo("hit " + F1 + " now"); // the static fallback is frame 1

        assertThat(plain(GlyphFrameText.advance(lore, glyphs, 0))).isEqualTo("hit " + F1 + " now");
        assertThat(plain(GlyphFrameText.advance(lore, glyphs, 4))).isEqualTo("hit " + F2 + " now");
        assertThat(plain(GlyphFrameText.advance(lore, glyphs, 8))).isEqualTo("hit " + F3 + " now");
        assertThat(plain(GlyphFrameText.advance(lore, glyphs, 12))).isEqualTo("hit " + F1 + " now");
    }

    @Test
    void stylesAndFontsSurviveTheRewrite() {
        RuntimeGlyphs glyphs = registry();
        Component styled = Component.text(String.valueOf(F1), NamedTextColor.WHITE).font(Key.key("sample:glyphs"));
        Component moved = GlyphFrameText.advance(Component.text("a").append(styled), glyphs, 4);
        Component child = moved.children().get(0);
        assertThat(plain(child)).isEqualTo(String.valueOf(F2));
        assertThat(child.color()).isEqualTo(NamedTextColor.WHITE);
        assertThat(child.font()).isEqualTo(Key.key("sample:glyphs"));
    }

    @Test
    void aPinnedAliasAndStillGlyphsAreLeftAlone() {
        RuntimeGlyphs glyphs = registry();
        Component pinned = GlyphText.resolve("<glyph:pinned><glyph:heart>", glyphs, GlyphText.Permissions.ALLOW_ALL);
        assertThat(GlyphFrameText.containsAnimation(pinned, glyphs)).isFalse();
        assertThat(GlyphFrameText.advance(pinned, glyphs, 4)).isSameAs(pinned);
        assertThat(plain(pinned)).isEqualTo(String.valueOf(F2) + (char) 42010);
    }

    @Test
    void nothingToAnimateReturnsTheSameInstance() {
        RuntimeGlyphs glyphs = registry();
        Component plainText = Component.text("no glyphs here");
        assertThat(GlyphFrameText.advance(plainText, glyphs, 8)).isSameAs(plainText);
        assertThat(GlyphFrameText.advance(plainText, RuntimeGlyphs.empty(), 8)).isSameAs(plainText);
        assertThat(GlyphFrameText.advance(plainText, null, 8)).isSameAs(plainText);
        assertThat(GlyphFrameText.containsAnimation(plainText, glyphs)).isFalse();
    }

    @Test
    void everyLineOfALoreAdvances() {
        RuntimeGlyphs glyphs = registry();
        var lines = List.of(
                GlyphText.resolve("<glyph:spark> one", glyphs, GlyphText.Permissions.ALLOW_ALL),
                Component.text("plain"),
                GlyphText.resolve("two <g:spark>", glyphs, GlyphText.Permissions.ALLOW_ALL));
        assertThat(GlyphFrameText.containsAnimation(lines, glyphs)).isTrue();
        var advanced = GlyphFrameText.advance(lines, glyphs, 8);
        assertThat(advanced.stream().map(GlyphFrameTextTest::plain).toList())
                .containsExactly(F3 + " one", "plain", "two " + F3);
        assertThat(advanced.get(1)).isSameAs(lines.get(1));
    }
}
