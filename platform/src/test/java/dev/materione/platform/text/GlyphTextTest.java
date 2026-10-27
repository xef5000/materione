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
                List.of(":heart:", "<3"), null, true, true, null, null, null));
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
                List.of(":love:", "<u"), "materione.glyphs.premium", true, false, null,
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
    void plainPlaceholdersResolveAsSubstrings() {
        // <3 is authored and tab-completed on sample:heart — it must resolve
        // too, or the completion list invites players into dead text (#79)
        Component resolved = GlyphText.resolve("I <3 u", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("I " + HEART_CHAR + " u");
        // two matches inside one literal run
        Component twice = GlyphText.resolve("<3<3", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(twice)).isEqualTo(HEART_CHAR + HEART_CHAR);
    }

    @Test
    void plainPlaceholderLongestMatchWins() {
        var entries = new LinkedHashMap<NamespacedId, RuntimeGlyphs.Entry>();
        entries.put(NamespacedId.parse("sample:heart"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:heart"), "sample:default", new int[] {42000},
                List.of("<33", "<3"), null, true, false, null, null, null));
        var glyphs = new RuntimeGlyphs(entries);
        Component resolved = GlyphText.resolve("x <33 y", glyphs, GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("x " + HEART_CHAR + " y");
    }

    @Test
    void deniedPlainPlaceholderStaysLiteral() {
        // <u is the premium love alias's plain placeholder: denied viewers see
        // the literal, no codepoint leak — same contract as :placeholders:
        Component denied = GlyphText.resolve("I <u u", registry(), permission -> false);
        assertThat(PlainTextComponentSerializer.plainText().serialize(denied)).isEqualTo("I <u u");
        Component allowed = GlyphText.resolve("I <u u", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(allowed)).isEqualTo("I " + HEART_CHAR + " u");
    }

    @Test
    void glyphTagsWorkForAnyGlyphAndForceWhite() {
        Component resolved = GlyphText.resolve("<glyph:star>", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo(STAR_CHAR);
        TextComponent leaf = leaf(resolved, STAR_CHAR);
        assertThat(leaf.color()).isEqualTo(NamedTextColor.WHITE);
        assertThat(leaf.font()).isNotNull();
        assertThat(leaf.font().toString()).isEqualTo("sample:default");
    }

    @Test
    void colorableArgKeepsSurroundingColor() {
        Component resolved = GlyphText.resolve("<red><g:star:colorable>", registry(), GlyphText.Permissions.ALLOW_ALL);
        // no forced white: the glyph takes the surrounding color
        assertThat(colorOf(resolved, STAR_CHAR)).isEqualTo(NamedTextColor.RED);
    }

    @Test
    void defaultFontGlyphsStayUnstyled() {
        Component resolved = GlyphText.resolve("<g:plain>", registry(), GlyphText.Permissions.ALLOW_ALL);
        TextComponent leaf = leaf(resolved, String.valueOf((char) 42002));
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
        // no plain placeholder may substring-match here: <nope:x> avoids <3 and <u
        Component resolved = GlyphText.resolve("time 12:30 <nope:x>", registry(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("time 12:30 <nope:x>");
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
        // the span's text is colored with the effect's signal
        assertThat(colorOf(resolved, "hi there")).as("span signal color").isEqualTo(TextColor.color(0xFF00FE));
        // the text before/after stays uncolored
        assertThat(colorOf(resolved, "before")).isNull();
        assertThat(colorOf(resolved, "after")).isNull();
    }

    @Test
    void shortEffectAliasAndGlyphsInsideSpan() {
        // <e:…> alias; a glyph inside the span keeps its forced white
        Component resolved = GlyphText.resolve(
                "<e:rainbow>a<glyph:star></e>",
                registry(), effects(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("a" + STAR_CHAR);
        assertThat(colorOf(resolved, "a")).isEqualTo(TextColor.color(0xFF00FE));
        assertThat(colorOf(resolved, STAR_CHAR)).isEqualTo(NamedTextColor.WHITE);
    }

    @Test
    void unknownEffectStaysLiteral() {
        Component resolved = GlyphText.resolve(
                "<effect:nope>x</effect>", registry(), effects(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved))
                .isEqualTo("<effect:nope>x</effect>");
    }

    @Test
    void unclosedEffectSpanRunsToTheEnd() {
        // MiniMessage semantics since #151 (plugin §6.1): an open tag closes at the end
        Component resolved = GlyphText.resolve(
                "<effect:rainbow>x", registry(), effects(), GlyphText.Permissions.ALLOW_ALL);
        assertThat(PlainTextComponentSerializer.plainText().serialize(resolved)).isEqualTo("x");
        assertThat(colorOf(resolved, "x")).isEqualTo(TextColor.color(0xFF00FE));
    }

    // --- tree helpers: assert what renders, not one tree shape ----------------

    /** The leaf text node whose content contains {@code text}. */
    private static TextComponent leaf(Component component, String text) {
        if (component instanceof TextComponent node && node.content().contains(text)) {
            return node;
        }
        for (Component child : component.children()) {
            TextComponent found = leaf(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The color {@code text} renders with: its own, else the nearest ancestor's. */
    private static TextColor colorOf(Component component, String text) {
        if (component instanceof TextComponent node && node.content().contains(text)) {
            return node.color();
        }
        for (Component child : component.children()) {
            if (leaf(child, text) != null) {
                TextColor inner = colorOf(child, text);
                return inner != null ? inner : component.color();
            }
        }
        return null;
    }

}
