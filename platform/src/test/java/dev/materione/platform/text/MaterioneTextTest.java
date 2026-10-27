package dev.materione.platform.text;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.materione.api.NamespacedId;
import dev.materione.core.runtime.RuntimeEffects;
import dev.materione.core.runtime.RuntimeGlyphs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The one text pipeline (plugin §6.1). The fixture cases are shared with the
 * editor's parser ({@code fixtures/text/rich-text.json}): what the editor
 * writes must mean the same thing here.
 */
class MaterioneTextTest {

    private static final char HEART = (char) 42000;
    private static final int SIGNAL = 0xFF00FE;

    private static RuntimeGlyphs glyphs() {
        var entries = new LinkedHashMap<NamespacedId, RuntimeGlyphs.Entry>();
        entries.put(NamespacedId.parse("sample:heart"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:heart"), null, new int[] {42000},
                List.of(":heart:", "<3"), null, true, false, null, null, null));
        entries.put(NamespacedId.parse("sample:crown"), new RuntimeGlyphs.Entry(
                NamespacedId.parse("sample:crown"), null, new int[] {42001},
                List.of(":crown:"), "materione.glyphs.crown", true, false, null, null, null));
        return new RuntimeGlyphs(entries);
    }

    private static RuntimeEffects effects() {
        var entries = new LinkedHashMap<NamespacedId, RuntimeEffects.Entry>();
        entries.put(NamespacedId.parse("sample:rainbow"), new RuntimeEffects.Entry(
                NamespacedId.parse("sample:rainbow"), "rainbow", 3.0f, 1.0f, SIGNAL));
        return new RuntimeEffects(entries);
    }

    private static Component parse(String text) {
        return MaterioneText.parse(text, glyphs(), effects(), GlyphText.Permissions.ALLOW_ALL);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // --- shared fixture ----------------------------------------------------------

    /** One rendered character and the style the client would draw it with. */
    private record Cell(char ch, TextColor color, boolean bold, boolean italic, boolean underline,
            boolean strikethrough, boolean obfuscated) {}

    private static List<Cell> cells(Component component) {
        var out = new ArrayList<Cell>();
        flatten(component, Style.empty(), out);
        return out;
    }

    private static void flatten(Component component, Style inherited, List<Cell> out) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            for (char ch : text.content().toCharArray()) {
                out.add(new Cell(ch, style.color(),
                        style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE,
                        style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE,
                        style.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE,
                        style.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE,
                        style.decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE));
            }
        }
        for (Component child : component.children()) {
            flatten(child, style, out);
        }
    }

    private static TextColor color(String spec) {
        if (spec == null) {
            return null;
        }
        return spec.startsWith("#") ? TextColor.fromHexString(spec) : NamedTextColor.NAMES.value(spec);
    }

    private static boolean flag(JsonObject run, String name) {
        return run.has(name) && run.get(name).getAsBoolean();
    }

    private static String text(JsonObject run, String name) {
        return run.has(name) ? run.get(name).getAsString() : null;
    }

    @TestFactory
    Stream<DynamicTest> sharedFixtureMeansTheSameHere() throws IOException {
        Path file = Path.of(System.getProperty("materione.fixtures.root"), "text", "rich-text.json");
        JsonArray cases = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("cases");
        var tests = new ArrayList<DynamicTest>();
        for (JsonElement element : cases) {
            JsonObject fixture = element.getAsJsonObject();
            if (fixture.has("java") && !fixture.get("java").getAsBoolean()) {
                continue;
            }
            String name = fixture.get("name").getAsString();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                assertMeaning(fixture.get("stored").getAsString(), fixture.getAsJsonArray("runs"));
                if (fixture.has("canonical")) {
                    assertMeaning(fixture.get("canonical").getAsString(), fixture.getAsJsonArray("runs"));
                }
            }));
        }
        assertThat(tests).hasSizeGreaterThan(20);
        return tests.stream();
    }

    private static void assertMeaning(String stored, JsonArray runs) {
        List<Cell> actual = cells(parse(stored));
        int at = 0;
        // consecutive gradient cells form one gradient element: its ends are the stops
        List<Integer> gradientStart = new ArrayList<>();
        String lastGradient = null;
        for (JsonElement element : runs) {
            JsonObject run = element.getAsJsonObject();
            String content = run.has("glyph") ? String.valueOf(HEART) : run.get("text").getAsString();
            String gradient = run.has("gradient") ? run.getAsJsonArray("gradient").toString() : null;
            if (gradient != null && !gradient.equals(lastGradient)) {
                gradientStart.add(at);
            }
            lastGradient = gradient;
            for (char ch : content.toCharArray()) {
                assertThat(at).as("%s: more characters than parsed", stored).isLessThan(actual.size());
                Cell cell = actual.get(at);
                String where = stored + " @" + at;
                assertThat(cell.ch()).as(where).isEqualTo(ch);
                if (run.has("glyph") && !flag(run, "colorable")) {
                    assertThat(cell.color()).as(where + " glyph forced white").isEqualTo(NamedTextColor.WHITE);
                } else if (run.has("effect")) {
                    assertThat(cell.color()).as(where + " effect signal").isEqualTo(TextColor.color(SIGNAL));
                } else if (gradient != null) {
                    assertThat(cell.color()).as(where + " gradient color").isNotNull();
                } else {
                    assertThat(Objects.equals(cell.color() == null ? null : cell.color().asHexString(),
                            color(text(run, "color")) == null ? null : color(text(run, "color")).asHexString()))
                            .as(where + " color " + cell.color()).isTrue();
                }
                assertThat(cell.bold()).as(where + " bold").isEqualTo(flag(run, "bold"));
                assertThat(cell.italic()).as(where + " italic").isEqualTo(flag(run, "italic"));
                assertThat(cell.underline()).as(where + " underline").isEqualTo(flag(run, "underline"));
                assertThat(cell.strikethrough()).as(where + " strikethrough").isEqualTo(flag(run, "strikethrough"));
                assertThat(cell.obfuscated()).as(where + " obfuscated").isEqualTo(flag(run, "obfuscated"));
                at++;
            }
        }
        assertThat(actual).as(stored + ": parsed length").hasSize(at);
        // gradient ends land exactly on the first and last stop
        int index = 0;
        for (JsonElement element : runs) {
            JsonObject run = element.getAsJsonObject();
            if (!run.has("gradient")) {
                continue;
            }
            JsonArray stops = run.getAsJsonArray("gradient");
            int start = gradientStart.get(Math.min(index, gradientStart.size() - 1));
            assertThat(actual.get(start).color()).as(stored + " gradient start")
                    .isEqualTo(TextColor.fromHexString(stops.get(0).getAsString()));
            index++;
            break;
        }
    }

    // --- styles ------------------------------------------------------------------

    @Test
    void gradientRunsAcrossFormattingAndEndsOnTheLastStop() {
        List<Cell> cells = cells(parse("<gradient:#ff0000:#00ff00:#0000ff>Ruby <b>Sword</b> X"));
        assertThat(cells.get(0).color()).isEqualTo(TextColor.fromHexString("#ff0000"));
        assertThat(cells.get(cells.size() - 1).color()).isEqualTo(TextColor.fromHexString("#0000ff"));
        // the middle stop is reached in the middle, not reset by the <b> child
        assertThat(cells.stream().map(Cell::color).distinct().count()).isGreaterThan(5);
        assertThat(cells.get(5).bold()).isTrue();
    }

    @Test
    void glyphInsideAGradientKeepsItsForcedWhite() {
        List<Cell> cells = cells(parse("<gradient:#ff0000:#0000ff>A<glyph:heart>B"));
        assertThat(cells).extracting(Cell::ch).containsExactly('A', HEART, 'B');
        assertThat(cells.get(1).color()).isEqualTo(NamedTextColor.WHITE);
        assertThat(cells.get(0).color()).isEqualTo(TextColor.fromHexString("#ff0000"));
    }

    @Test
    void shiftEmojiAndEffectCoexistWithStyles() {
        Component component = parse("<red>a<shift:-3>b :heart: <effect:rainbow>hot</effect> cold");
        String text = plain(component);
        // 3 = 2 + 1: two advance characters between a and b
        assertThat(text).startsWith("a").contains("b " + HEART + " hot cold");
        assertThat(text.indexOf('b')).isEqualTo(3);
        List<Cell> cells = cells(component);
        Cell hot = cells.get(text.indexOf("hot"));
        assertThat(hot.color()).isEqualTo(TextColor.color(SIGNAL));
        Cell cold = cells.get(text.indexOf("cold"));
        assertThat(cold.color()).isEqualTo(NamedTextColor.RED);
    }

    @Test
    void plainPlaceholdersResolveInParsedText() {
        assertThat(plain(parse("I <3 u"))).isEqualTo("I " + HEART + " u");
        // canonical editor output escapes the angle: it still resolves
        assertThat(plain(parse("I \\<3 u"))).isEqualTo("I " + HEART + " u");
    }

    @Test
    void deniedAndUnknownMarkersStayLiteral() {
        Component denied = MaterioneText.parse(
                "<glyph:crown> :crown: <glyph:nope> <effect:nope>x</effect>", glyphs(), effects(), permission -> false);
        assertThat(plain(denied)).isEqualTo("<glyph:crown> :crown: <glyph:nope> <effect:nope>x</effect>");
        Component noRegistries = MaterioneText.parse("<g:heart> :heart:", null, null, GlyphText.Permissions.ALLOW_ALL);
        assertThat(plain(noRegistries)).isEqualTo("<g:heart> :heart:");
    }

    @Test
    void interactiveTagsAreNotPartOfTheStoredForm() {
        String text = "<click:run_command:'/op me'>x</click><hover:show_text:'hi'>y</hover><insert:z>w</insert>";
        Component component = parse(text);
        assertThat(plain(component)).isEqualTo(text);
        assertThat(component.clickEvent()).isNull();
    }

    @Test
    void malformedInputNeverThrows() {
        for (String text : List.of("<", ">", "<<>>", "</b>", "<gradient:nothex>x", "<#zzzzzz>x", "&", "&x&1",
                "&#12", "<glyph:>", "<shift:x>", "<effect:>x</effect>", "\\", "<b", "a</gradient>")) {
            assertThat(parse(text)).as(text).isNotNull();
        }
        assertThat(plain(parse("<gradient:nothex>x"))).isEqualTo("<gradient:nothex>x");
    }

    // --- legacy --------------------------------------------------------------------

    @Test
    void legacyBridgeMapsEveryCode() {
        assertThat(MaterioneText.legacyToMiniMessage("Plain & simple")).isEqualTo("Plain & simple");
        assertThat(MaterioneText.legacyToMiniMessage("&0&1&2&3&4&5&6&7&8&9&a&b&c&d&e&fx"))
                .endsWith("<white>x").contains("</black><dark_blue>");
        assertThat(MaterioneText.legacyToMiniMessage("&k&l&m&n&ox"))
                .isEqualTo("<obf><b><st><u><i>x");
        assertThat(MaterioneText.legacyToMiniMessage("&l&cx&ry"))
                .isEqualTo("<b></b><red>x</red>y");
        assertThat(MaterioneText.legacyToMiniMessage("§x§1§2§a§b§e§fx")).isEqualTo("<#12abef>x");
        // a mismatched marker inside the repeated form is not a hex code
        assertThat(MaterioneText.legacyToMiniMessage("&x&1§2&a&b&e&fx")).doesNotContain("#");
        // tags the author wrote are never closed by a legacy code
        assertThat(MaterioneText.legacyToMiniMessage("<effect:x>&cHot</effect>"))
                .isEqualTo("<effect:x><red>Hot</effect>");
    }

    @Test
    void legacyStringsRenderAsBefore() {
        // the pre-#151 renderer, for strings without MiniMessage-looking text
        var before = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();
        for (String text : List.of("&dRuby disc", "&6Ruby &lSword", "&cRed&r plain", "&l&nLoud&cRed",
                "&#12ABEFdirect", "&x&1&2&a&b&e&fexpanded", "&ksecret &rclear", "Salt & Pepper", "&mgone&8 grey")) {
            assertThat(cells(parse(text))).as(text).isEqualTo(cells(before.deserialize(text)));
        }
    }

    @Test
    void legacySectionOutputDegradesGradientsPerCharacter() {
        String legacy = MaterioneText.legacySection(parse("<red>Hi <b>there"));
        assertThat(legacy).isEqualTo("§cHi §lthere");
        String gradient = MaterioneText.legacySection(parse("<gradient:#ff0000:#0000ff>ab"));
        assertThat(gradient).isEqualTo("§x§f§f§0§0§0§0a§x§0§0§0§0§f§fb");
    }

    @Test
    void itemTextKeepsItalicsOffAroundRichText() {
        Component item = ItemText.text("<gradient:#f9731e:#ffd27a>Ruby</gradient> <i>Sword");
        assertThat(item.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        List<Cell> cells = cells(item);
        assertThat(cells.get(0).italic()).isFalse();
        assertThat(cells.get(cells.size() - 1).italic()).isTrue();
    }

    @Test
    void emptyAndNullAreEmpty() {
        assertThat(MaterioneText.parse("", glyphs(), effects(), GlyphText.Permissions.ALLOW_ALL))
                .isEqualTo(Component.empty());
        assertThat(MaterioneText.parse(null, glyphs(), effects(), GlyphText.Permissions.ALLOW_ALL))
                .isEqualTo(Component.empty());
    }
}
