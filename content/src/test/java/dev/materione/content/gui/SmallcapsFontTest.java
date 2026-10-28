package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.core.texture.PngDecoder;
import org.junit.jupiter.api.Test;

/**
 * The placeholder small-caps font: its width table is the client's rule applied to its own
 * sheet, so a width can never drift from the artwork.
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
                    int luminance = sheet.argb()[(originY + row) * sheet.width() + originX + column] & 0xFF;
                    if (luminance != 0) {
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
    void lowercaseUsesTheCapitalShapesAndSpaceHasItsOwnWidth() {
        assertThat(SmallcapsFont.advance('a')).isEqualTo(SmallcapsFont.advance('A'));
        assertThat(SmallcapsFont.advance(' ')).isEqualTo(SmallcapsFont.SPACE_ADVANCE);
        assertThat(SmallcapsFont.measure("Shop")).isEqualTo(
                SmallcapsFont.advance('S') + SmallcapsFont.advance('H')
                        + SmallcapsFont.advance('O') + SmallcapsFont.advance('P'));
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
}
