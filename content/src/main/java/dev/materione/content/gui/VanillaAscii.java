package dev.materione.content.gui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The advance widths of the client's own default font, printable ASCII (gui.md section 9.4, #202).
 *
 * <p><strong>Numbers, not artwork.</strong> The vanilla texture {@code minecraft:font/ascii.png} is
 * referenced by resource location and never shipped. What the compiler needs from it is only the
 * advance of each glyph, which the client derives from the glyph's own pixels: the rightmost ink
 * column plus one, plus one pixel of spacing. Those 95 numbers are the table in
 * {@code fonts/vanilla-ascii/advances.txt}, produced by {@code scripts/gui/derive-vanilla-ascii-advances.mjs}
 * from the client jar, with its provenance in the file's header.
 *
 * <p><strong>One source of truth.</strong> The studio reads the same file ({@code font/vanilla-ascii.ts}),
 * so the two sides cannot disagree about a width. {@code fixtures/gui/vanilla-ascii/golden.txt} pins
 * the table's digest for both implementations.
 *
 * <p>With these advances the compiler knows where a {@code default} run ends, so text may follow it
 * on the same title. Vertical placement still limits a {@code default} run to row 5 or lower; that
 * is the cell height, not a width.
 */
public final class VanillaAscii {

    /** The source file's classpath location (copied from {@code fonts/} by the content build). */
    static final String SOURCE_RESOURCE = "/vanilla-ascii/advances.txt";

    /** The first and last printable ASCII codepoints the table covers. */
    public static final int FIRST = 0x20;
    public static final int LAST = 0x7E;

    private static final Pattern CODE_LINE = Pattern.compile("0x([0-9A-Fa-f]{2}) (\\d+)");
    private static final Pattern SPACE_LINE = Pattern.compile("space (\\d+)");

    /** The advance of a space: the client's {@code include/space} provider. */
    public static final int SPACE_ADVANCE;

    private static final int[] ADVANCES = new int[LAST - FIRST + 1];

    static {
        Map<Integer, Integer> parsed = parse(readSource());
        SPACE_ADVANCE = parsed.get(FIRST);
        for (int codepoint = FIRST; codepoint <= LAST; codepoint++) {
            ADVANCES[codepoint - FIRST] = parsed.get(codepoint);
        }
    }

    private VanillaAscii() {}

    /** Whether the default font draws {@code codepoint}: printable ASCII only. */
    public static boolean supports(int codepoint) {
        return codepoint >= FIRST && codepoint <= LAST;
    }

    /**
     * The advance of one character in the default font.
     *
     * @throws IllegalArgumentException when {@code codepoint} is not {@link #supports supported}
     */
    public static int advance(int codepoint) {
        if (codepoint == ' ') {
            return SPACE_ADVANCE;
        }
        if (!supports(codepoint)) {
            throw new IllegalArgumentException("the default font has no advance for U+" + Integer.toHexString(codepoint));
        }
        return ADVANCES[codepoint - FIRST];
    }

    /** The advance of a whole string; every character must be {@link #supports supported}. */
    public static int measure(String text) {
        int total = 0;
        for (int index = 0; index < text.length(); index++) {
            total += advance(text.charAt(index));
        }
        return total;
    }

    // --- source ---------------------------------------------------------------

    private static String readSource() {
        try (InputStream in = VanillaAscii.class.getResourceAsStream(SOURCE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + SOURCE_RESOURCE + " (fonts/vanilla-ascii/advances.txt)");
            }
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                var text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
                return text.toString();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + SOURCE_RESOURCE, failure);
        }
    }

    /** Parses the table: {@code space N} and {@code 0xHH N} lines; ';' starts a comment. */
    static Map<Integer, Integer> parse(String text) {
        var advances = new HashMap<Integer, Integer>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith(";")) {
                continue;
            }
            var space = SPACE_LINE.matcher(line);
            if (space.matches()) {
                put(advances, ' ', Integer.parseInt(space.group(1)), line);
                continue;
            }
            var code = CODE_LINE.matcher(line);
            if (!code.matches()) {
                throw new IllegalStateException("vanilla-ascii: unrecognised line: " + line);
            }
            put(advances, Integer.parseInt(code.group(1), 16), Integer.parseInt(code.group(2)), line);
        }
        for (int codepoint = FIRST; codepoint <= LAST; codepoint++) {
            if (!advances.containsKey(codepoint)) {
                throw new IllegalStateException("vanilla-ascii: no advance for U+" + Integer.toHexString(codepoint));
            }
        }
        return advances;
    }

    private static void put(Map<Integer, Integer> advances, int codepoint, int advance, String line) {
        if (advance <= 0) {
            throw new IllegalStateException("vanilla-ascii: non-positive advance: " + line);
        }
        if (advances.put(codepoint, advance) != null) {
            throw new IllegalStateException("vanilla-ascii: U+" + Integer.toHexString(codepoint) + " is defined twice");
        }
    }
}
