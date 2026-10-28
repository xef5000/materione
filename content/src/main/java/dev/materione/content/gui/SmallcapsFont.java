package dev.materione.content.gui;

import dev.materione.content.gui.raster.PngEncoder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code smallcaps} title font (gui.md section 4): the first-party bitmap font every
 * {@code text} layer uses by default.
 *
 * <p><strong>One source of truth.</strong> The artwork lives in {@code fonts/smallcaps/smallcaps.txt}
 * at the repository root, copied onto the classpath by the content build. The studio reads the
 * same file, so its preview is the compiled sheet. Nothing about the glyphs is written in code.
 *
 * <p><strong>Metrics.</strong> The cell is {@value #CELL_WIDTH}×{@value #CELL_HEIGHT} px and holds
 * the art in its top seven rows: capitals are {@value #CAP_HEIGHT} px tall (rows 0–5), lowercase
 * is a small capital {@value #SMALL_CAP_HEIGHT} px tall on the same baseline, and row 6 holds
 * descenders. A glyph's advance is the client's bitmap rule applied to its own pixels: rightmost
 * ink column plus one, plus one pixel of spacing. The width table therefore cannot drift from the
 * artwork, and {@code fixtures/gui/smallcaps/advances.txt} pins it for both implementations.
 *
 * <p>Layout: one RGBA sheet of {@value #COLUMNS} columns × {@value #ROWS} rows of cells. Cell
 * {@code k} holds codepoint {@code 0x20 + k}. The space cell is left blank (a {@code space}
 * provider supplies its advance), so the chars grid maps it to {@code U+0000}, which the loader
 * skips. Ink is opaque white and the rest is transparent: the client tints the ink with the text
 * colour, and the transparency is what lets the title's background show through.
 */
public final class SmallcapsFont {

    /** The source file's classpath location (copied from {@code fonts/smallcaps/}). */
    static final String SOURCE_RESOURCE = "/smallcaps/smallcaps.txt";

    /** The sheet cell, in px. The cell is taller than the art so any title row ≤ 13 keeps {@code ascent ≤ height}. */
    public static final int CELL_WIDTH = 8;
    public static final int CELL_HEIGHT = 16;
    public static final int COLUMNS = 16;
    public static final int ROWS = 6;

    /** Capital height and small-capital height, in px, as the source declares them. */
    public static final int CAP_HEIGHT = 6;
    public static final int SMALL_CAP_HEIGHT = 5;

    /** Rows per glyph in the source: the cap band (0–5) and the descender row (6). */
    static final int GLYPH_ROWS = 7;

    private static final int FIRST = 0x20;
    private static final int LAST = 0x7E;
    private static final Pattern GLYPH_HEADER = Pattern.compile("glyph U\\+([0-9A-Fa-f]{4}) (.)");
    private static final Pattern CELL_HEADER = Pattern.compile("cell (\\d+) (\\d+)");
    private static final Pattern SPACE_HEADER = Pattern.compile("space (\\d+)");
    private static final Pattern METRIC_HEADER = Pattern.compile("(cap|small) (\\d+)");
    private static final Pattern ROW = Pattern.compile("[#.]+");

    /** The parsed source: the space advance and the art of every printable glyph, seven rows each. */
    private record Source(int space, Map<Integer, String[]> glyphs) {}

    private static final Source FONT = parse(readSource());

    /** The advance of a space: the space provider's width, from the source. */
    public static final int SPACE_ADVANCE = FONT.space();

    /** The advance of every printable glyph: ink width + 1, applied to the art (see class comment). */
    private static final Map<Integer, Integer> WIDTH = new HashMap<>();

    static {
        for (var entry : FONT.glyphs().entrySet()) {
            WIDTH.put(entry.getKey(), inkWidth(entry.getValue()) + 1);
        }
    }

    private static final int[] SHEET_ARGB = buildSheet();
    private static final byte[] SHEET_PNG = PngEncoder.encodeRgba(
            COLUMNS * CELL_WIDTH, ROWS * CELL_HEIGHT, SHEET_ARGB);

    private SmallcapsFont() {}

    /** Whether the font draws {@code codepoint}: printable ASCII only. */
    public static boolean supports(int codepoint) {
        return codepoint >= FIRST && codepoint <= LAST;
    }

    /**
     * The advance of one character, by the client's bitmap rule: the glyph's ink width plus one
     * pixel of spacing; a space uses its provider's width.
     *
     * @throws IllegalArgumentException when {@code codepoint} is not {@link #supports supported}
     */
    public static int advance(int codepoint) {
        if (codepoint == ' ') {
            return SPACE_ADVANCE;
        }
        Integer width = WIDTH.get(codepoint);
        if (width == null) {
            throw new IllegalArgumentException("smallcaps does not draw U+" + Integer.toHexString(codepoint));
        }
        return width;
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

    /** The art rows of a printable glyph, top to bottom, {@value #GLYPH_ROWS} rows: for tests and tools. */
    static List<String> rows(int codepoint) {
        String[] rows = FONT.glyphs().get(codepoint);
        if (rows == null) {
            throw new IllegalArgumentException("smallcaps has no art for U+" + Integer.toHexString(codepoint));
        }
        return List.of(rows);
    }

    // --- source ---------------------------------------------------------------

    private static List<String> readSource() {
        try (InputStream in = SmallcapsFont.class.getResourceAsStream(SOURCE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SOURCE_RESOURCE + " is not on the classpath: the content build"
                        + " must copy fonts/smallcaps/smallcaps.txt");
            }
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + SOURCE_RESOURCE, failure);
        }
    }

    /** Parses the source format documented at the top of {@code fonts/smallcaps/smallcaps.txt}. */
    private static Source parse(List<String> lines) {
        int space = -1;
        var glyphs = new HashMap<Integer, String[]>();
        Integer codepoint = null;
        var rows = new ArrayList<String>();
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (line.isEmpty() || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("glyph ")) {
                closeGlyph(codepoint, rows, glyphs);
                Matcher header = GLYPH_HEADER.matcher(line);
                if (!header.matches()) {
                    throw new IllegalArgumentException("smallcaps: bad glyph header: " + line);
                }
                codepoint = Integer.parseInt(header.group(1), 16);
                if (header.group(2).charAt(0) != codepoint) {
                    throw new IllegalArgumentException("smallcaps: header character does not match U+"
                            + header.group(1) + ": " + line);
                }
                rows.clear();
                continue;
            }
            if (codepoint != null) {
                if (!ROW.matcher(line).matches() || line.length() > CELL_WIDTH) {
                    throw new IllegalArgumentException("smallcaps: bad art row under U+"
                            + Integer.toHexString(codepoint) + ": " + line);
                }
                rows.add(line);
                continue;
            }
            Matcher cell = CELL_HEADER.matcher(line);
            if (cell.matches()) {
                if (Integer.parseInt(cell.group(1)) != CELL_WIDTH || Integer.parseInt(cell.group(2)) != CELL_HEIGHT) {
                    throw new IllegalArgumentException("smallcaps: the source cell " + line
                            + " does not match the sheet cell " + CELL_WIDTH + " " + CELL_HEIGHT);
                }
                continue;
            }
            Matcher spaceHeader = SPACE_HEADER.matcher(line);
            if (spaceHeader.matches()) {
                space = Integer.parseInt(spaceHeader.group(1));
                continue;
            }
            Matcher metric = METRIC_HEADER.matcher(line);
            if (metric.matches()) {
                int height = Integer.parseInt(metric.group(2));
                int expected = metric.group(1).equals("cap") ? CAP_HEIGHT : SMALL_CAP_HEIGHT;
                if (height != expected) {
                    throw new IllegalArgumentException("smallcaps: " + line + " disagrees with the compiled "
                            + expected + " px");
                }
                continue;
            }
            throw new IllegalArgumentException("smallcaps: unrecognised line: " + line);
        }
        closeGlyph(codepoint, rows, glyphs);
        if (space < 0) {
            throw new IllegalArgumentException("smallcaps: the source has no space line");
        }
        for (int cp = FIRST + 1; cp <= LAST; cp++) {
            if (!glyphs.containsKey(cp)) {
                throw new IllegalArgumentException("smallcaps: no glyph for U+" + Integer.toHexString(cp));
            }
        }
        return new Source(space, Map.copyOf(glyphs));
    }

    private static void closeGlyph(Integer codepoint, List<String> rows, Map<Integer, String[]> glyphs) {
        if (codepoint == null) {
            return;
        }
        if (rows.size() != GLYPH_ROWS) {
            throw new IllegalArgumentException("smallcaps: U+" + Integer.toHexString(codepoint) + " has "
                    + rows.size() + " rows, expected " + GLYPH_ROWS);
        }
        if (glyphs.put(codepoint, rows.toArray(String[]::new)) != null) {
            throw new IllegalArgumentException("smallcaps: U+" + Integer.toHexString(codepoint) + " is defined twice");
        }
    }

    // --- rendering ---------------------------------------------------------------

    /** Columns from the first ink column to the last, plus one: the client's {@code actualWidth}. */
    private static int inkWidth(String[] rows) {
        int width = 0;
        for (String row : rows) {
            width = Math.max(width, row.lastIndexOf('#') + 1);
        }
        return width;
    }

    /**
     * The sheet as RGBA: ink is opaque white and everything else is fully transparent
     * ({@code 0x00000000}). The client tints a glyph by multiplying its colour with the text colour,
     * and it takes transparency from the alpha channel. A grayscale sheet has no alpha, so its
     * background loads as opaque black and every glyph sits on a black cell (observed on 26.1.2).
     */
    private static int[] buildSheet() {
        int width = COLUMNS * CELL_WIDTH;
        int[] argb = new int[width * ROWS * CELL_HEIGHT];
        for (var entry : FONT.glyphs().entrySet()) {
            int codepoint = entry.getKey();
            int cell = codepoint - FIRST;
            int originX = (cell % COLUMNS) * CELL_WIDTH;
            int originY = (cell / COLUMNS) * CELL_HEIGHT;
            String[] art = entry.getValue();
            for (int row = 0; row < art.length; row++) {
                for (int column = 0; column < art[row].length(); column++) {
                    if (art[row].charAt(column) == '#') {
                        argb[(originY + row) * width + originX + column] = 0xFFFFFFFF;
                    }
                }
            }
        }
        return argb;
    }
}
