package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.platform.text.MaterioneText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * The container title goes through the one text pipeline (gui.md §4, step 4): the background
 * glyph, the shift tags and the offset-font text runs must come out as the components the client
 * draws, with no tag text left in the title.
 */
class GuiTitleTest {

    /** The title shape {@code GuiTitle} assembles for a GUI named "Shop" on a 1-glyph background. */
    private static final String COMPILED_TITLE =
            "<shift:-8><font:demo:gui><#FFFFFF></font><shift:177>"
                    + "<font:demo:gui/smallcaps_y6><#404040>Shop</font>";

    @Test
    void theTitleParsesToTheBackgroundGlyphAndTheAuthoredText() {
        Component title = MaterioneText.parseData(COMPILED_TITLE);
        String plain = PlainTextComponentSerializer.plainText().serialize(title);

        assertThat(plain).contains("");
        assertThat(plain).endsWith("Shop");
        assertThat(plain).doesNotContain("<").doesNotContain("font:").doesNotContain("shift");
    }

    @Test
    void theTitleKeepsItsFontsAsStyles() {
        Component title = MaterioneText.parseData(COMPILED_TITLE);

        // the background run is drawn with the GUI font, the text with its offset font
        assertThat(allFonts(title)).contains("demo:gui", "demo:gui/smallcaps_y6");
    }

    private static java.util.Set<String> allFonts(Component component) {
        var fonts = new java.util.HashSet<String>();
        collectFonts(component, fonts);
        return fonts;
    }

    private static void collectFonts(Component component, java.util.Set<String> fonts) {
        if (component.style().font() != null) {
            fonts.add(component.style().font().asString());
        }
        for (Component child : component.children()) {
            collectFonts(child, fonts);
        }
    }
}
