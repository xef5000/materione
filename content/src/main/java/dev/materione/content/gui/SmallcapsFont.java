package dev.materione.content.gui;

import dev.materione.content.gui.raster.PngEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code smallcaps} title font (gui.md §4, "Fonts for text layers").
 *
 * <p><strong>Placeholder artwork.</strong> This is a minimal, original 3×5 pixel font drawn
 * in code so the pipeline works end to end: printable ASCII ({@code 0x20..0x7E}), lowercase
 * drawn with the capital shapes. It is not the release artwork; the research issue (#188)
 * replaces the glyph table, and everything else (the sheet layout, the width rule, the
 * providers) keeps working because widths are derived from the same pixels.
 *
 * <p>Layout: one grayscale sheet of {@value #COLUMNS} columns × {@value #ROWS} rows of
 * {@value #CELL_WIDTH}×{@value #CELL_HEIGHT} cells. Cell {@code k} holds codepoint
 * {@code 0x20 + k}; the art sits in the top five rows of its cell. The space cell is left
 * unmapped (a {@code space} provider supplies its advance), so the sheet's grid maps
 * {@code 0x20..0x7F} with the two blank cells as {@code U+0000}, which the loader skips.
 *
 * <p>Grayscale is deliberate: the client tints an intensity (grayscale) glyph with the
 * text colour, which is how a {@code color:} on a text layer takes effect on this font.
 */
public final class SmallcapsFont {

    public static final int CELL_WIDTH = 8;
    /** The cell is taller than the art so any title y ≤ 13 keeps {@code ascent ≤ height}. */
    public static final int CELL_HEIGHT = 16;
    public static final int COLUMNS = 16;
    public static final int ROWS = 6;
    /** The advance of a space: the space provider's width (matches the client's own space). */
    public static final int SPACE_ADVANCE = 4;

    private static final int FIRST = 0x20;
    private static final int LAST = 0x7E;
    private static final int ART_ROWS = 5;

    /** Glyph art by capital letter or symbol; rows use {@code #} for ink, {@code .} for none. */
    private static final Map<Character, String[]> ART = new HashMap<>();

    static {
        glyph('!', ".#.", ".#.", ".#.", "...", ".#.");
        glyph('"', "#.#", "#.#", "...", "...", "...");
        glyph('#', "#.#", "###", "#.#", "###", "#.#");
        glyph('$', ".##", "##.", ".#.", ".##", "##.");
        glyph('%', "#.#", "..#", ".#.", "#..", "#.#");
        glyph('&', ".#.", "#.#", ".#.", "#.#", ".##");
        glyph('\'', ".#.", ".#.", "...", "...", "...");
        glyph('(', "..#", ".#.", ".#.", ".#.", "..#");
        glyph(')', "#..", ".#.", ".#.", ".#.", "#..");
        glyph('*', "...", "#.#", ".#.", "#.#", "...");
        glyph('+', "...", ".#.", "###", ".#.", "...");
        glyph(',', "...", "...", "...", ".#.", "#..");
        glyph('-', "...", "...", "###", "...", "...");
        glyph('.', "...", "...", "...", "...", ".#.");
        glyph('/', "..#", "..#", ".#.", "#..", "#..");
        glyph('0', "###", "#.#", "#.#", "#.#", "###");
        glyph('1', ".#.", "##.", ".#.", ".#.", "###");
        glyph('2', "##.", "..#", ".#.", "#..", "###");
        glyph('3', "##.", "..#", ".##", "..#", "##.");
        glyph('4', "#.#", "#.#", "###", "..#", "..#");
        glyph('5', "###", "#..", "##.", "..#", "##.");
        glyph('6', ".##", "#..", "###", "#.#", "###");
        glyph('7', "###", "..#", ".#.", ".#.", ".#.");
        glyph('8', "###", "#.#", "###", "#.#", "###");
        glyph('9', "###", "#.#", "###", "..#", "##.");
        glyph(':', "...", ".#.", "...", ".#.", "...");
        glyph(';', "...", ".#.", "...", ".#.", "#..");
        glyph('<', "..#", ".#.", "#..", ".#.", "..#");
        glyph('=', "...", "###", "...", "###", "...");
        glyph('>', "#..", ".#.", "..#", ".#.", "#..");
        glyph('?', "##.", "..#", ".#.", "...", ".#.");
        glyph('@', ".##", "#.#", "#.#", "#..", ".##");
        glyph('A', ".#.", "#.#", "###", "#.#", "#.#");
        glyph('B', "##.", "#.#", "##.", "#.#", "##.");
        glyph('C', ".##", "#..", "#..", "#..", ".##");
        glyph('D', "##.", "#.#", "#.#", "#.#", "##.");
        glyph('E', "###", "#..", "##.", "#..", "###");
        glyph('F', "###", "#..", "##.", "#..", "#..");
        glyph('G', ".##", "#..", "#.#", "#.#", ".##");
        glyph('H', "#.#", "#.#", "###", "#.#", "#.#");
        glyph('I', "###", ".#.", ".#.", ".#.", "###");
        glyph('J', "..#", "..#", "..#", "#.#", ".#.");
        glyph('K', "#.#", "#.#", "##.", "#.#", "#.#");
        glyph('L', "#..", "#..", "#..", "#..", "###");
        glyph('M', "#...#", "##.##", "#.#.#", "#...#", "#...#");
        glyph('N', "#..#", "##.#", "#.##", "#..#", "#..#");
        glyph('O', ".#.", "#.#", "#.#", "#.#", ".#.");
        glyph('P', "##.", "#.#", "##.", "#..", "#..");
        glyph('Q', ".#.", "#.#", "#.#", "##.", ".##");
        glyph('R', "##.", "#.#", "##.", "#.#", "#.#");
        glyph('S', ".##", "#..", ".#.", "..#", "##.");
        glyph('T', "###", ".#.", ".#.", ".#.", ".#.");
        glyph('U', "#.#", "#.#", "#.#", "#.#", "###");
        glyph('V', "#.#", "#.#", "#.#", "#.#", ".#.");
        glyph('W', "#...#", "#...#", "#.#.#", "##.##", "#...#");
        glyph('X', "#.#", "#.#", ".#.", "#.#", "#.#");
        glyph('Y', "#.#", "#.#", ".#.", ".#.", ".#.");
        glyph('Z', "###", "..#", ".#.", "#..", "###");
        glyph('[', "##", "#.", "#.", "#.", "##");
        glyph('\\', "#..", "#..", ".#.", "..#", "..#");
        glyph(']', "##", ".#", ".#", ".#", "##");
        glyph('^', ".#.", "#.#", "...", "...", "...");
        glyph('_', "...", "...", "...", "...", "###");
        glyph('`', "#..", ".#.", "...", "...", "...");
        glyph('{', ".##", ".#.", "##.", ".#.", ".##");
        glyph('|', ".#", ".#", ".#", ".#", ".#");
        glyph('}', "##.", ".#.", ".##", ".#.", "##.");
        glyph('~', "...", ".#.", "#.#", "..#", "...");
    }

    /** Ink width per glyph, cached from the art. */
    private static final Map<Character, Integer> WIDTH = new HashMap<>();

    static {
        for (var entry : ART.entrySet()) {
            WIDTH.put(entry.getKey(), inkWidth(entry.getValue()));
        }
    }

    private static final byte[] SHEET_LUMINANCE = buildSheet();
    private static final byte[] SHEET_PNG = PngEncoder.encodeGrayscale(
            COLUMNS * CELL_WIDTH, ROWS * CELL_HEIGHT, SHEET_LUMINANCE);

    private SmallcapsFont() {}

    /** Whether the font draws {@code codepoint} (printable ASCII only in this placeholder). */
    public static boolean supports(int codepoint) {
        return codepoint >= FIRST && codepoint <= LAST;
    }

    /**
     * The advance of one character, by the client's bitmap rule: the cell's ink width
     * plus one pixel of spacing ({@code actualWidth + 1}); a space uses its provider's width.
     */
    public static int advance(int codepoint) {
        if (codepoint == ' ') {
            return SPACE_ADVANCE;
        }
        return WIDTH.get(glyphKey(codepoint)) + 1;
    }

    /** The advance of a whole string; every character must be {@link #supports supported}. */
    public static int measure(String text) {
        int total = 0;
        for (int index = 0; index < text.length(); index++) {
            total += advance(text.charAt(index));
        }
        return total;
    }

    /** The grayscale PNG sheet, identical bytes on every call. */
    public static byte[] sheetPng() {
        return SHEET_PNG.clone();
    }

    /**
     * The sheet's chars grid for a bitmap provider: {@value #ROWS} strings of
     * {@value #COLUMNS} characters. Unused cells (space, DEL) are {@code U+0000}.
     */
    public static List<String> grid() {
        var rows = new ArrayList<String>(ROWS);
        for (int row = 0; row < ROWS; row++) {
            var line = new StringBuilder(COLUMNS);
            for (int column = 0; column < COLUMNS; column++) {
                int codepoint = FIRST + row * COLUMNS + column;
                line.append(codepoint == ' ' || codepoint > LAST ? '\u0000' : (char) codepoint);
            }
            rows.add(line.toString());
        }
        return List.copyOf(rows);
    }

    private static char glyphKey(int codepoint) {
        char upper = Character.toUpperCase((char) codepoint);
        return ART.containsKey(upper) ? upper : (char) codepoint;
    }

    private static void glyph(char key, String... rows) {
        ART.put(key, rows);
    }

    /** Columns from the first ink column to the last, plus one: the client's {@code actualWidth}. */
    private static int inkWidth(String[] rows) {
        int width = 0;
        for (String row : rows) {
            width = Math.max(width, row.lastIndexOf('#') + 1);
        }
        return width;
    }

    private static byte[] buildSheet() {
        int width = COLUMNS * CELL_WIDTH;
        byte[] luminance = new byte[width * ROWS * CELL_HEIGHT];
        for (int codepoint = FIRST; codepoint <= LAST; codepoint++) {
            if (codepoint == ' ') {
                continue;
            }
            int cell = codepoint - FIRST;
            int originX = (cell % COLUMNS) * CELL_WIDTH;
            int originY = (cell / COLUMNS) * CELL_HEIGHT;
            String[] art = ART.get(glyphKey(codepoint));
            for (int row = 0; row < ART_ROWS; row++) {
                for (int column = 0; column < art[row].length(); column++) {
                    if (art[row].charAt(column) == '#') {
                        luminance[(originY + row) * width + originX + column] = (byte) 0xFF;
                    }
                }
            }
        }
        return luminance;
    }
}
