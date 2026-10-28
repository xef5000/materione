package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.core.texture.PngDecoder;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The smallcaps font (gui.md section 4, fonts/smallcaps/README.md): its width table is the client's
 * rule applied to its own sheet, the metrics are the ones the README promises, and the table the
 * studio also tests against is the same file.
 */
class SmallcapsFontTest {

    @Test
    void theWidthTableIsTheClientRuleAppliedToTheSheetPixels() {
        var sheet = PngDecoder.decode(SmallcapsFont.sheetPng()).orElseThrow();
        assertThat(sheet.width()).isEqualTo(SmallcapsFont.COLUMNS * SmallcapsFont.CELL_WIDTH);
        assertThat(sheet.height()).isEqualTo(SmallcapsFont.ROWS * SmallcapsFont.CELL_HEIGHT);
        for (int codepoint = 0x21; codepoint <= 0x7E; codepoint++) {
            int cell = codepoint - 0x20;
            int originX = (cell % SmallcapsFont.COLUMNS) * SmallcapsFont.CELL_WIDTH;
            int originY = (cell / SmallcapsFont.COLUMNS) * SmallcapsFont.CELL_HEIGHT;
            int ink = 0;
            for (int column = 0; column < SmallcapsFont.CELL_WIDTH; column++) {
                for (int row = 0; row < SmallcapsFont.CELL_HEIGHT; row++) {
                    int alpha = sheet.argb()[(originY + row) * sheet.width() + originX + column] >>> 24;
                    if (alpha != 0) {
                        ink = column + 1; // rightmost column with any ink
                    }
                }
            }
            assertThat(SmallcapsFont.advance(codepoint))
                    .as("advance of %c", (char) codepoint)
                    .isEqualTo(ink + 1);
        }
    }

    @Test
    void theSheetIsTransparentExceptForTheInk() {
        // a grayscale sheet loads as opaque black cells (observed on 26.1.2): the background must be alpha 0
        var sheet = PngDecoder.decode(SmallcapsFont.sheetPng()).orElseThrow();
        int[] argb = sheet.argb();
        int opaque = 0;
        int transparent = 0;
        for (int pixel : argb) {
            if (pixel >>> 24 == 0) {
                transparent++;
            } else {
                opaque++;
                assertThat(pixel).as("ink is white, tinted by the text colour").isEqualTo(0xFFFFFFFF);
            }
        }
        assertThat(opaque).isPositive();
        assertThat(transparent).isGreaterThan(opaque);
    }

    @Test
    void theWidthTableMatchesTheSharedFixture() throws IOException {
        // fixtures/gui/smallcaps/advances.txt is the table the studio tests against as well
        var expected = new HashMap<Integer, Integer>();
        int space = -1;
        for (String raw : Files.readAllLines(GoldenFiles.fixturesRoot().resolve("gui/smallcaps/advances.txt"))) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith(";")) {
                continue;
            }
            String[] parts = line.split(" ");
            if (parts[0].equals("space")) {
                space = Integer.parseInt(parts[1]);
            } else {
                expected.put(Integer.decode(parts[0]), Integer.parseInt(parts[1]));
            }
        }
        assertThat(SmallcapsFont.SPACE_ADVANCE).isEqualTo(space);
        assertThat(expected).hasSize(0x7E - 0x21 + 1);
        for (Map.Entry<Integer, Integer> entry : expected.entrySet()) {
            assertThat(SmallcapsFont.advance(entry.getKey()))
                    .as("advance of U+%04X", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void capitalsAreSixPixelsTallAndLowercaseIsASmallCapOnTheSameBaseline() {
        for (char capital = 'A'; capital <= 'Z'; capital++) {
            assertThat(inkRows(capital))
                    .as("rows of %c", capital)
                    .contains(0)
                    .allMatch(row -> row <= SmallcapsFont.CAP_HEIGHT - 1)
                    .contains(SmallcapsFont.CAP_HEIGHT - 1);
        }
        for (char lowercase = 'a'; lowercase <= 'z'; lowercase++) {
            // a small capital: row 0 is blank, and the baseline (row 5) is shared with the capitals
            assertThat(inkRows(lowercase))
                    .as("rows of %c", lowercase)
                    .allMatch(row -> row >= 1 && row <= SmallcapsFont.CAP_HEIGHT - 1)
                    .contains(SmallcapsFont.CAP_HEIGHT - 1);
        }
        assertThat(SmallcapsFont.SMALL_CAP_HEIGHT).isEqualTo(SmallcapsFont.CAP_HEIGHT - 1);
    }

    @Test
    void lowercaseIsNotTheCapitalShape() {
        // the release font draws lowercase as small capitals: row 0 is inked for 'A' and blank for 'a'
        assertThat(SmallcapsFont.rows('a')).isNotEqualTo(SmallcapsFont.rows('A'));
        assertThat(SmallcapsFont.rows('A').get(0)).contains("#");
        assertThat(SmallcapsFont.rows('a').get(0)).doesNotContain("#");
    }

    @Test
    void spaceHasItsOwnWidth() {
        assertThat(SmallcapsFont.advance(' ')).isEqualTo(SmallcapsFont.SPACE_ADVANCE);
        assertThat(SmallcapsFont.measure("Shop")).isEqualTo(
                SmallcapsFont.advance('S') + SmallcapsFont.advance('h')
                        + SmallcapsFont.advance('o') + SmallcapsFont.advance('p'));
    }

    @Test
    void supportsPrintableAsciiOnly() {
        assertThat(SmallcapsFont.supports(0x20)).isTrue();
        assertThat(SmallcapsFont.supports(0x7E)).isTrue();
        assertThat(SmallcapsFont.supports(0x1F)).isFalse();
        assertThat(SmallcapsFont.supports(0x7F)).isFalse();
        assertThat(SmallcapsFont.supports(0xE9)).isFalse();
    }

    @Test
    void theGridMapsEveryPrintableCellExceptSpace() {
        var grid = SmallcapsFont.grid();
        assertThat(grid).hasSize(SmallcapsFont.ROWS);
        assertThat(grid.get(0).charAt(0)).isEqualTo('\u0000'); // space is the provider's, not a cell
        assertThat(grid.get(0).charAt(1)).isEqualTo('!');
        assertThat(grid.get(3).charAt(1)).isEqualTo('Q'); // row 3, column 1: U+0020 + 3·16 + 1 = U+0051
    }

    /** The rows of a glyph that carry ink, as a set of row indices (the art is seven rows). */
    private static java.util.Set<Integer> inkRows(char character) {
        var rows = new java.util.TreeSet<Integer>();
        var art = SmallcapsFont.rows(character);
        for (int row = 0; row < art.size(); row++) {
            if (art.get(row).indexOf('#') >= 0) {
                rows.add(row);
            }
        }
        return rows;
    }
}
