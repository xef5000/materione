package dev.materione.platform.text;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeEffects;
import dev.materione.core.runtime.RuntimeGlyphs;
import java.util.LinkedHashMap;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** The text pipeline: tags, placeholders, shifts, permission gating. */
class GlyphTextTest {

    private static final String HEART_CHAR = String.valueOf((char) 42000);
    private static final String STAR_CHAR = String.valueOf((char) 42001);

    private RuntimeGlyphs registry() {
        var entries = new LinkedHashMap<NamespacedId, RuntimeGlyphs.Entry>();
        entries.put(NamespacedId.parse("sample:heart"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:heart"), "sample:default", new int[] {42000},
                List.of(":heart:"), null, true, true, null, null, null));
        entries.put(NamespacedId.parse("sample:star"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:star"), "sample:default", new int[] {42001},
                List.of(), null, false, false, null, null, null));
        // a default-font glyph: null key, rendered unstyled through
        // minecraft:default (which references the pack's glyph font)
        entries.put(NamespacedId.parse("sample:plain"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:plain"), null, new int[] {42002},
                List.of(), null, false, false, null, null, null));
        // alias: borrows the heart's bitmap, own placeholder + permission
        entries.put(NamespacedId.parse("sample:love"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:love"), "sample:default", new int[0],
                List.of(":love:"), "materione.glyphs.premium", true, false, null,
                "sample:heart", null));
        return new RuntimeGlyphs(entries);
    }

    @Test
    void emojiPlaceholdersResolveForEmojiGlyphs() {
        Component resolved = GlyphText.resolve("hi :heart:!", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("hi " + HEART_CHAR + "!");
    }

    @Test
    void nonEmojiGlyphsIgnorePlaceholders() {
        // star has no placeholder at all; nothing is consumed
        Component resolved = GlyphText.resolve(":star:", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo(":star:");
    }

    @Test
    void glyphTagsWorkForAnyGlyphAndForceWhite() {
        Component resolved = GlyphText.resolve("<glyph:star>", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo(STAR_CHAR);
        TextComponent leaf = (TextComponent) resolved.children().get(0);
        assertThat(leaf.color()).isEqualTo(NamedTextColor.WHITE);
        assertThat(leaf.font()).isNotNull();
        assertThat(leaf.font().toString()).isEqualTo("sample:default");
    }

    @Test
    void colorableArgKeepsSurroundingColor() {
        Component resolved = GlyphText.resolve("<g:star:colorable>", registry(), GlyphText.Permissions.ALLOW_ALL);
        TextComponent leaf = (TextComponent) resolved.children().get(0);
        assertThat(leaf.color()).isNull();
    }

    @Test
    void defaultFontGlyphsStayUnstyled() {
        Component resolved = GlyphText.resolve("<g:plain>", registry(), GlyphText.Permissions.ALLOW_ALL);
        TextComponent leaf = (TextComponent) resolved.children().get(0);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved))
                .isEqualTo(String.valueOf((char) 42002));
        // no font style: the client's minecraft:default references the pack font
        assertThat(leaf.font()).isNull();
    }

    @Test
    void deniedPlaceholderStaysLiteral() {
        Component resolved = GlyphText.resolve(":love:", registry(), permission -> false);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo(":love:");
    }

    @Test
    void permittedAliasResolvesThroughItsTarget() {
        Component resolved = GlyphText.resolve(":love:", registry(), permission -> true);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo(HEART_CHAR);
    }

    @Test
    void shiftTagsDecomposeIntoAdvanceChars() {
        Component resolved = GlyphText.resolve("a<shift:-3>b", registry(), GlyphText.Permissions.ALLOW_ALL);
        String plain = PlainTextComponentSerializer.plainText().serialize(resolved);
        // 3 = 2 + 1 → two invisible shift chars between a and b
        assertThat(plain).hasSize(1 + 2 + 1);
        assertThat(plain.charAt(0)).isEqualTo('a');
        assertThat(plain.charAt(plain.length() - 1)).isEqualTo('b');
    }

    @Test
    void unknownTagsAndColonsStayLiteral() {
        Component resolved = GlyphText.resolve("time 12:30 <unknown:x>", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("time 12:30 <unknown:x>");
    }

    @Test
    void legacyCodesInLiteralsSurvive() {
        Component resolved = GlyphText.resolve("&ahi :heart:", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("hi " + HEART_CHAR);
    }

    @Test
    void emptyRegistryPassesThrough() {
        Component resolved = GlyphText.resolve(":heart: <g:x>", RuntimeGlyphs.empty(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo(":heart: <g:x>");
    }

    @Test
    void componentResolutionRewritesLeafTextInPlace() {
        Component message = Component.text().append(Component.text("say :heart: now")).build();
        Component resolved = GlyphText.resolveComponent(message, registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("say " + HEART_CHAR + " now");
        // untouched input returns the same instance
        Component clean = Component.text("nothing here");
        assertThat(GlyphText.resolveComponent(clean, registry(), GlyphText.Permissions.ALLOW_ALL)).isSameAs(clean);
    }

    @Test
    void referenceIndexSpecSelectsCells() {
        int[] cells = GlyphText.select(new int[] {10, 11, 12, 13}, "2..3");
        assertThat(cells).containsExactly(11, 12);
        assertThat(GlyphText.select(new int[] {10, 11}, null)).containsExactly(10);
        assertThat(GlyphText.select(new int[] {10, 11}, "9")).containsExactly(10);
    }

    // --- effect spans --------------------------------------------------------

    private RuntimeEffects effects() {
        var entries = new LinkedHashMap<NamespacedId, RuntimeEffects.Entry>();
        entries.put(NamespacedId.parse("sample:rainbow"), new RuntimeEffects.Entry(
                NamespacedId.parse("sample:rainbow"), "rainbow", 3.0f, 1.0f, 0xFF00FE));
        return new RuntimeEffects(entries);
    }

    @Test
    void effectSpanColorsPlainRunsWithTheSignal() {
        Component resolved = GlyphText.resolve(
                "before <effect:rainbow>hi there</effect> after",
                registry(), effects(), GlyphText.Permissions.ALLOW_ALL);
        // the span becomes one child colored with the effect's signal
        TextComponent span = resolved.children().stream()
                .filter(c -> PlainTextComponentSerializer.plainText().serialize(c).contains("hi there"))
                .map(c -> (TextComponent) c)
                .findFirst().orElseThrow();
        assertThat(span.color()).as("span signal color").isEqualTo(TextColor.color(0xFF00FE));
        // the text before/after stays uncolored
        TextComponent plain = (TextComponent) resolved.children().get(0);
        assertThat(plain.color()).isNull();
    }

    @Test
    void shortEffectAliasAndGlyphsInsideSpan() {
        // <e:…> alias; a glyph inside the span keeps its forced white —
        // the span wraps one resolved child whose own children carry the text
        Component resolved = GlyphText.resolve(
                "<e:rainbow>a<glyph:star></e>",
                registry(), effects(), GlyphText.Permissions.ALLOW_ALL);
        TextComponent span = (TextComponent) resolved.children().get(0);
        assertThat(span.color()).isEqualTo(TextColor.color(0xFF00FE));
        String plain = PlainTextComponentSerializer.plainText().serialize(span);
        assertThat(plain).isEqualTo("a" + STAR_CHAR);
        TextComponent glyph = (TextComponent) span.children().get(0).children().get(1);
        assertThat(glyph.color()).isEqualTo(NamedTextColor.WHITE);
    }

    @Test
    void unknownEffectStaysLiteral() {
        Component resolved = GlyphText.resolve(
                "<effect:nope>x</effect>", registry(), effects(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved))
                .isEqualTo("<effect:nope>x</effect>");
    }

    @Test
    void unclosedEffectSpanStaysLiteral() {
        Component resolved = GlyphText.resolve(
                "<effect:rainbow>x", registry(), effects(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved))
                .isEqualTo("<effect:rainbow>x");
    }

}
