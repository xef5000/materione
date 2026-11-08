package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.GuiContainer;
import dev.materione.api.gui.TitleSink;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The title origin and the trailing shift come from the container profile (gui.md §12.5.3). The arithmetic is the
 * one verified on a real dispenser screen (§12.2.6, U1): a centred title is {@code imageWidth - 16} wide, so the
 * client's {@code (imageWidth - width) / 2} puts the pen at 8, and a trailing shift may be negative.
 */
class GuiTitleOriginTest {

    private static GuiContainer container(String id) {
        return GuiContainers.byId(id).orElseThrow();
    }

    private static final GuiTitle.ArtGlyph ART = new GuiTitle.ArtGlyph(0xE200, 0, 176);

    @Test
    void aCentredProfileStartsItsPenAtEightAndEndsItAt168() {
        GuiContainer dispenser = container("dispenser");
        assertThat(GuiTitle.originX(dispenser)).isEqualTo(8);
        assertThat(GuiTitle.trailingX(dispenser)).isEqualTo(168);
    }

    @Test
    void aTitleOfTheDispenserEndsWithTheShiftThatMakesItsAdvanceExactly160() {
        var title = new GuiTitle("ns", container("dispenser"));
        title.art(List.of(ART)); // pen: 8 -> -8.. tile 0..176 ends at 177, back to 8
        title.place(8, 6, "Hi", TitleSink.Font.SMALLCAPS, 0x404040);
        int pen = 8 + SmallcapsFont.measure("Hi");
        assertThat(title.build()).endsWith("<shift:" + (168 - pen) + ">");
        assertThat(168 - pen).isPositive();
    }

    @Test
    void theTrailingShiftIsNegativeWhenTheArtRanPast168() {
        var title = new GuiTitle("ns", container("dispenser"));
        title.art(List.of(new GuiTitle.ArtGlyph(0xE200, 400, 100))); // pen ends at 501, back to 8, then...
        title.place(400, 6, "x", TitleSink.Font.DEFAULT, 0x404040); // a run far to the right
        int pen = 400 + VanillaAscii.measure("x");
        assertThat(pen).isGreaterThan(168);
        assertThat(title.build()).endsWith("<shift:" + (168 - pen) + ">");
    }

    @Test
    void noShiftIsAddedWhenThePenIsAlreadyAt168() {
        var title = new GuiTitle("ns", container("dispenser"));
        title.place(168 - VanillaAscii.measure("a"), 6, "a", TitleSink.Font.DEFAULT, 0x404040);
        assertThat(title.build()).doesNotMatch(".*<shift:-?[0-9]+>$");
        assertThat(title.build()).endsWith("</font>");
    }

    @Test
    void aTitleWithNoPieceIsEmptyEvenOnACentredProfile() {
        assertThat(new GuiTitle("ns", container("dispenser")).build()).isEmpty();
    }

    @Test
    void buildingTwiceGivesTheSameTitle() {
        var title = new GuiTitle("ns", container("dispenser"));
        title.place(8, 6, "Hi", TitleSink.Font.SMALLCAPS, 0x404040);
        assertThat(title.build()).isEqualTo(title.build());
    }

    @Test
    void aProfileWhoseTitleIsNotCentredGetsNoTrailingShiftAtAll() {
        for (String id : List.of("chest_1", "chest_6", "hopper")) {
            GuiContainer container = container(id);
            var title = new GuiTitle("ns", container);
            title.art(List.of(ART));
            title.place(8, 6, "Hi", TitleSink.Font.SMALLCAPS, 0x404040);
            assertThat(GuiTitle.originX(container)).as(id).isEqualTo(8);
            assertThat(title.build()).as(id).endsWith("Hi</font>");
        }
    }

    @Test
    void anArtPieceStartsAndEndsAtTheProfilesOrigin() {
        var title = new GuiTitle("ns", container("hopper"));
        title.art(List.of(ART));
        // the pen starts at 8; the tile at column 0 is drawn from -8, ends at 177, and the pen goes back to 8
        assertThat(title.build()).isEqualTo("<shift:-8><font:ns:gui><#FFFFFF></font><shift:-169>");
    }

    @Test
    void theAscentFollowsTheProfilesTitleRow() {
        GuiContainer hopper = container("hopper");
        assertThat(GuiTitle.ascentFor(hopper, 0)).isEqualTo(hopper.titleY() + 7);
        assertThat(GuiTitle.ascentFor(hopper, 6)).isEqualTo(7);
        assertThat(GuiTitle.ascentFor(hopper, -243)).isEqualTo(256);
    }
}
