package dev.materione.content.gui.raster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.materione.api.gui.TextureSource;
import dev.materione.core.texture.PngDecoder;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The vanilla panel corners (gui.md section 11.6) against the owner's chest art: the top two 12 x 12 corner blocks
 * of {@code webui/static/vanilla/generic_54.png} and its outer outline columns must be reproduced exactly by the
 * shared vector {@code vanilla-chest-corners} (a 176 x 125 vanilla-corner panel, the top part of a 6-row chest).
 * The bottom edge of the chest's top part is the inventory separator, not a bevel, so only the bottom corners'
 * outline pixels are compared.
 */
class VanillaChestCornersTest {

    private static final Path REPO = GoldenFiles.fixturesRoot().getParent();

    @Test
    void theVectorCornersMatchTheChestArtPixelForPixel() throws IOException {
        Path chest = REPO.resolve("webui").resolve("static").resolve("vanilla").resolve("generic_54.png");
        Path expected = GoldenFiles.fixturesRoot().resolve("gui").resolve("raster").resolve("vanilla-chest-corners.png");
        assumeTrue(Files.isRegularFile(chest) && Files.isRegularFile(expected),
                "the chest art or the shared vector is absent");

        var art = PngDecoder.decode(Files.readAllBytes(chest))
                .orElseThrow(() -> new AssertionError("generic_54.png is unreadable"));
        var panel = PngDecoder.decode(Files.readAllBytes(expected))
                .orElseThrow(() -> new AssertionError("vanilla-chest-corners.png is unreadable"));
        int width = panel.width();
        int height = panel.height();
        assertThat(width).as("panel width").isEqualTo(176);
        assertThat(height).as("panel height (rows * 18 + 17 for six rows)").isEqualTo(6 * 18 + 17);

        var mismatches = new ArrayList<String>();
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                compare(art, panel, x, y, "top-left", mismatches);
                compare(art, panel, width - 12 + x, y, "top-right", mismatches);
            }
        }
        for (int y = 0; y < height; y++) {
            compare(art, panel, 0, y, "left outline", mismatches);
            compare(art, panel, width - 1, y, "right outline", mismatches);
        }
        compare(art, panel, 0, height - 1, "bottom-left corner", mismatches);
        compare(art, panel, width - 1, height - 1, "bottom-right corner", mismatches);
        assertThat(mismatches).as("pixels of the vector differing from generic_54.png").isEmpty();
    }

    private static void compare(TextureSource.Pixels art, TextureSource.Pixels panel, int x, int y, String label,
            List<String> mismatches) {
        int want = normalise(art.argb()[y * art.width() + x]);
        int got = normalise(panel.argb()[y * panel.width() + x]);
        if (want != got && mismatches.size() < 20) {
            mismatches.add(String.format("%s (%d,%d) chest %08X vector %08X", label, x, y, want, got));
        }
    }

    private static int normalise(int argb) {
        return (argb >>> 24) == 0 ? 0 : argb;
    }
}
