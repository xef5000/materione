package dev.materione.content.gui.logic;

import static dev.materione.content.gui.GuiTestSupport.definition;
import static dev.materione.content.gui.GuiTestSupport.layer;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.ClickArea;
import dev.materione.api.gui.TextureSource;
import dev.materione.content.gui.GuiCompiler;
import dev.materione.content.gui.GuiDefinition;
import dev.materione.content.gui.GuiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The click map (logic.md §6.2): which layers a click on each container slot reaches, topmost first.
 * The rectangle rule is "the centre of the slot's 16 x 16 interior lies in the rectangle"; slot sets
 * are exact; hidden and skipped layers take nothing; overlaps list the later layer first.
 */
class ClickMapCompilerTest {

    private static final TextureSource NO_TEXTURES = reference -> Optional.empty();

    @SafeVarargs
    @SuppressWarnings("varargs")
    private static ClickMap map(int rows, Map<String, Object>... layers) {
        GuiDefinition gui = GuiTestSupport.parse(GuiTestSupport.type(), definition(rows, layers));
        return ClickMapCompiler.compile(gui, NO_TEXTURES, Set.of());
    }

    private static Map<String, Object> button(String id, int x, int y, int width, int height) {
        return layer(id, "button", "x", x, "y", y, "width", width, "height", height);
    }

    @Test
    void aSlotItemLayerCoversExactlyItsSlot() {
        ClickMap clicks = map(3, layer("sword", "item", "slot", 13, "item", "minecraft:diamond_sword"));

        assertThat(clicks.layersAt(13)).containsExactly("sword");
        assertThat(clicks.layersAt(12)).isEmpty();
        assertThat(clicks.slotsOf("sword")).containsExactly(13);
    }

    @Test
    void aRectangleTakesTheSlotsWhoseCentreItContains() {
        // slot 0's item origin is (8, 18): its interior centre is pixel (16, 26)
        ClickMap exact = map(1, button("b", 8, 18, 16, 16));
        assertThat(exact.slotsOf("b")).containsExactly(0);

        // one pixel short of the centre on either axis: not covered
        assertThat(map(1, button("b", 8, 18, 8, 16)).slotsOf("b")).isEmpty(); // x range 8..15, centre x = 16
        assertThat(map(1, button("b", 8, 18, 16, 8)).slotsOf("b")).isEmpty(); // y range 18..25, centre y = 26
        // and one pixel more takes it: the rectangle is half-open, so [8, 17) contains 16
        assertThat(map(1, button("b", 8, 18, 9, 9)).slotsOf("b")).containsExactly(0);
    }

    @Test
    void aWideButtonCoversAWholeRowOfSlots() {
        // nine slots of the second row: centres y = 44, x = 16, 34, ..., 160
        ClickMap clicks = map(3, button("row", 8, 36, 160, 16));

        assertThat(clicks.slotsOf("row")).containsExactly(9, 10, 11, 12, 13, 14, 15, 16, 17);
    }

    @Test
    void aRectangleThatOnlyGrazesASlotEdgeDoesNotTakeIt() {
        // covers pixels 8..23 x 18..33 (the whole of slot 0) plus a 2 px sliver of slot 1 (x 26 is its origin)
        ClickMap clicks = map(1, button("b", 8, 18, 19, 16));

        assertThat(clicks.slotsOf("b")).containsExactly(0);
    }

    @Test
    void overlappingLayersListTheTopmostFirst() {
        ClickMap clicks = map(1,
                layer("wells", "slots", "region", "container"),
                button("pad", 8, 18, 16, 16),
                layer("gem", "item", "slot", 0, "item", "minecraft:diamond"));

        assertThat(clicks.layersAt(0)).containsExactly("gem", "pad", "wells");
        assertThat(clicks.layersAt(1)).containsExactly("wells");
    }

    @Test
    void aHiddenLayerTakesNoClicks() {
        ClickMap clicks = map(1,
                layer("wells", "slots", "region", "container"),
                layer("gem", "item", "slot", 0, "item", "minecraft:diamond", "visible", false));

        assertThat(clicks.layersAt(0)).containsExactly("wells");
        assertThat(clicks.layers()).doesNotContain("gem");
    }

    @Test
    void slotWellsOfThePlayerInventoryAreNotPartOfTheMap() {
        assertThat(map(2, layer("wells", "slots", "region", "player")).isEmpty()).isTrue();
        assertThat(map(2, layer("wells", "slots", "region", "all")).slotsOf("wells")).hasSize(18);
    }

    @Test
    void layersWithoutAnAreaAreNotClickable() {
        ClickMap clicks = map(1,
                layer("frame", "panel", "width", 176, "height", 132),
                layer("art", "paint", "image", "gui/art"));

        assertThat(clicks.isEmpty()).isTrue();
        assertThat(clicks).isSameAs(ClickMap.EMPTY);
    }

    @Test
    void textIsClickableOverItsMeasuredWidth() {
        // smallcaps text at (8, 20): wider than 8 px and 7 rows tall, so it reaches slot 0's centre (16, 26)
        ClickMap clicks = map(1, layer("label", "text", "text", "HI HI HI HI", "x", 8, "y", 20));

        assertThat(clicks.slotsOf("label")).contains(0);
    }

    @Test
    void anUnsizedImageIsAsBigAsItsTexture() {
        GuiDefinition gui = GuiTestSupport.parse(GuiTestSupport.type(),
                definition(1, layer("icon", "image", "image", "gui/icon", "x", 8, "y", 18)));
        TextureSource sixteen = reference -> Optional.of(new TextureSource.Pixels(16, 16, new int[256]));

        assertThat(ClickMapCompiler.compile(gui, sixteen, Set.of()).slotsOf("icon")).containsExactly(0);
        assertThat(ClickMapCompiler.compile(gui, NO_TEXTURES, Set.of()).isEmpty())
                .as("a missing texture means no area").isTrue();
    }

    @Test
    void aLayerTheCompilerSkippedTakesNoClicks() {
        GuiDefinition gui = GuiTestSupport.parse(GuiTestSupport.type(),
                definition(1, button("b", 8, 18, 16, 16)));

        assertThat(ClickMapCompiler.compile(gui, NO_TEXTURES, Set.of("b")).isEmpty()).isTrue();
    }

    @Test
    void theGuiCompileCarriesTheMapAndTheRules() {
        var raw = definition(1, button("b", 8, 18, 16, 16));
        raw.put("behavior", List.of(Map.of("on", "gui.click", "do", List.of("gui.close"))));
        GuiDefinition gui = GuiTestSupport.parse(GuiTestSupport.type(), raw);

        var model = GuiCompiler.compile(gui, 0xE200, NO_TEXTURES).model();

        assertThat(model.clicks().layersAt(0)).containsExactly("b");
        assertThat(model.rules().behavior()).hasSize(1);
    }

    @Test
    void theAreaShapesResolveToContainerSlotsOnly() {
        assertThat(ClickMapCompiler.coveredSlots(new ClickArea.Slots(Set.of(2, 40)), 1)).containsExactly(2);
        assertThat(ClickMapCompiler.coveredSlots(ClickArea.NONE, 3)).isEmpty();
        assertThat(ClickMapCompiler.coveredSlots(new ClickArea.Rect(0, 0, 0, 0), 3)).isEmpty();
    }
}
