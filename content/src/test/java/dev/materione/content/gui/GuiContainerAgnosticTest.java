package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The ban of gui.md §12.5.6 and §12.13 item 5: the system is agnostic of the container type, so the GUI code never
 * mentions a chest's geometry. Outside the profile data ({@code shared/gui/containers.json}), the GUI sources of the
 * content, api and plugin modules may not carry
 *
 * <ul>
 *   <li>the identifier {@code rows} (a chest's row count: the container is an id, and the grid is the profile's
 *       {@code columns} and {@code slotCount});
 *   <li>{@code % 9} and {@code / 9} (the chest's nine columns), the literals {@code 176} and {@code 114} (the
 *       chest's width and height base), and a {@code "chest} string literal (a chest id, or the word);
 * </ul>
 *
 * <p>Comments and the contents of string literals are not code and are skipped for the numeric rules; the
 * {@code "chest} rule reads the literals. The allow-list is every {@code rows} that is not a container row: the
 * glyph sheets, the art tile grid and the rows of list entries; and the one default a New GUI starts with.
 */
class GuiContainerAgnosticTest {

    /** The directories whose Java is GUI code. */
    private static final List<String> SCANNED = List.of(
            "content/src/main/java/dev/materione/content/gui",
            "api/src/main/java/dev/materione/api/gui",
            "plugin/src/main/java/dev/materione/plugin/gui");

    /** Files where {@code rows} means something else, and why. */
    private static final Map<String, String> ROWS_ARE_NOT_CONTAINER_ROWS = Map.of(
            "SmallcapsFont.java", "the glyph sheet's rows of cells",
            "GuiFonts.java", "the vanilla ascii.png grid's rows",
            "GuiArt.java", "the art tile grid's rows",
            "Rendered.java", "the entries of a list layer",
            "SlotRenderer.java", "the entries of a list layer");

    /** Files that may name the default container a New GUI starts with (the chest is that, and nothing more). */
    private static final Map<String, String> MAY_NAME_THE_DEFAULT = Map.of(
            "GuiType.java", "NEW_GUI_CONTAINER, the container the New GUI dialog starts with (gui.md §12.4)");

    private static final Pattern ROWS = Pattern.compile("\\brows\\b");
    private static final Pattern NINE = Pattern.compile("[%/]\\s*9\\b");
    private static final Pattern CHEST_WIDTH = Pattern.compile("\\b(176|114)\\b");
    private static final Pattern CHEST_LITERAL = Pattern.compile("\"chest[_\"]");

    @Test
    void noGuiCodeKnowsAChestsGeometryOrNamesAChest() throws IOException {
        Path repo = GoldenFiles.fixturesRoot().getParent();
        var offenders = new ArrayList<String>();
        for (String directory : SCANNED) {
            Path root = repo.resolve(directory);
            assertThat(root).as("scanned directory " + directory).isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String name = file.getFileName().toString();
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    String code = withoutCommentsOrLiterals(text, false);
                    String literals = withoutCommentsOrLiterals(text, true);
                    String where = repo.relativize(file).toString().replace('\\', '/');
                    if (!ROWS_ARE_NOT_CONTAINER_ROWS.containsKey(name) && ROWS.matcher(code).find()) {
                        offenders.add(where + ": the identifier 'rows'");
                    }
                    if (NINE.matcher(code).find()) {
                        offenders.add(where + ": '% 9' or '/ 9'");
                    }
                    if (CHEST_WIDTH.matcher(code).find()) {
                        offenders.add(where + ": 176 or 114");
                    }
                    if (!MAY_NAME_THE_DEFAULT.containsKey(name) && CHEST_LITERAL.matcher(literals).find()) {
                        offenders.add(where + ": a \"chest literal");
                    }
                }
            }
        }
        assertThat(offenders)
                .as("GUI code is container-agnostic: the geometry is the profile's (gui.md §12.5.6)")
                .isEmpty();
    }

    @Test
    void theBanSeesWhatItBans() {
        // a guard on the scanner itself, so a broken stripper cannot turn the ban into a pass
        assertThat(ROWS.matcher(withoutCommentsOrLiterals("int n = rows * 9;", false)).find()).isTrue();
        assertThat(NINE.matcher(withoutCommentsOrLiterals("int c = slot % 9;", false)).find()).isTrue();
        assertThat(CHEST_WIDTH.matcher(withoutCommentsOrLiterals("int w = 176;", false)).find()).isTrue();
        assertThat(CHEST_LITERAL.matcher(withoutCommentsOrLiterals("var id = \"chest_3\";", true)).find()).isTrue();
        assertThat(ROWS.matcher(withoutCommentsOrLiterals("// rows\n/* rows */ var s = \"rows\";", false)).find())
                .isFalse();
        assertThat(CHEST_WIDTH.matcher(withoutCommentsOrLiterals("var s = \"a chest is 176\";", false)).find())
                .isFalse();
    }

    /**
     * Java source with comments removed. String and character literals are blanked to their quotes
     * ({@code keepLiterals == false}) or kept ({@code true}), so a rule reads either the code or the literals.
     */
    static String withoutCommentsOrLiterals(String source, boolean keepLiterals) {
        var out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? source.length() : end + 3;
                out.append(keepLiterals ? source.substring(i, stop) : "\"\"");
                i = stop;
            } else if (c == '"' || c == '\'') {
                int start = i++;
                while (i < source.length() && source.charAt(i) != c) {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(i + 1, source.length());
                out.append(keepLiterals ? source.substring(start, i) : "" + c + c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
