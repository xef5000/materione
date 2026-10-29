package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.content.gui.CompiledGui;
import dev.materione.core.runtime.RuntimeItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The live-refresh decisions (gui.md §5), without a server: which GUIs a swap changed or
 * removed, and what each viewer's open window needs. The window's title and size are fixed at
 * creation, so only a change to them forces a new window; items are written in place.
 */
class GuiRefreshPlanTest {

    private static final NamespacedId SHOP = NamespacedId.parse("demo:shop");
    private static final NamespacedId ARENA = NamespacedId.parse("demo:arena");
    private static final NamespacedId SWORD = NamespacedId.parse("demo:sword");
    /** The title a viewer's window was created with: the compiled MiniMessage title, as the runtime stores it. */
    private static final String OPEN_TITLE = "<font:demo:gui>Shop";

    @Test
    void anUnchangedSwapChangesNothing() {
        assertThat(GuiRefreshPlan.changes(view("hash-1", shop("Shop", 3)), view("hash-1", shop("Shop", 3))))
                .isEmpty();
    }

    @Test
    void aGuiWhoseTitleChangedIsChangedAndOneThatVanishedIsRemoved() {
        RuntimeGuis before = view("hash-1", shop("Shop", 3), arena());
        RuntimeGuis after = view("hash-1", shop("Market", 3));

        assertThat(GuiRefreshPlan.changes(before, after))
                .containsExactly(
                        Map.entry(SHOP, GuiRefreshPlan.Change.CHANGED),
                        Map.entry(ARENA, GuiRefreshPlan.Change.REMOVED));
    }

    @Test
    void aGuiWhoseSlotItemChangedIsChanged() {
        RuntimeGuis before = view("hash-1", shop("Shop", 3));
        RuntimeGuis after = view("hash-2", shop("Shop", 3));

        assertThat(GuiRefreshPlan.changes(before, after)).containsExactly(
                Map.entry(SHOP, GuiRefreshPlan.Change.CHANGED));
    }

    @Test
    void aNewGuiIsNotAChangeBecauseNobodyViewsIt() {
        assertThat(GuiRefreshPlan.changes(view("hash-1"), view("hash-1", shop("Shop", 3)))).isEmpty();
    }

    @Test
    void aWindowOfAGuiThatIsNoLongerLiveIsClosed() {
        assertThat(GuiRefreshPlan.viewerAction(OPEN_TITLE, 3, Optional.empty()))
                .isEqualTo(GuiRefreshPlan.Action.CLOSE);
    }

    @Test
    void aNewTitleOrNewRowsNeedANewWindow() {
        RuntimeGuis retitled = view("hash-1", shop("Market", 3));
        assertThat(GuiRefreshPlan.viewerAction(OPEN_TITLE, 3, retitled.gui(SHOP)))
                .isEqualTo(GuiRefreshPlan.Action.REOPEN);

        RuntimeGuis resized = view("hash-1", shop("Shop", 4));
        assertThat(GuiRefreshPlan.viewerAction(OPEN_TITLE, 3, resized.gui(SHOP)))
                .isEqualTo(GuiRefreshPlan.Action.REOPEN);
    }

    @Test
    void newItemsOnTheSameWindowAreWrittenInPlace() {
        RuntimeGuis now = view("hash-2", shop("Shop", 3));

        assertThat(GuiRefreshPlan.viewerAction(OPEN_TITLE, 3, now.gui(SHOP)))
                .isEqualTo(GuiRefreshPlan.Action.UPDATE_CONTENTS);
    }

    // --- fixtures -----------------------------------------------------------

    /** A view of the given GUIs, where the sword resolves to an item with this definition hash. */
    private static RuntimeGuis view(String swordHash, CompiledGui... guis) {
        var models = new LinkedHashMap<NamespacedId, CompiledGui>();
        for (CompiledGui gui : guis) {
            models.put(gui.id(), gui);
        }
        RuntimeItem sword = new RuntimeItem(SWORD, "PAPER", null, "Sword", List.of(), swordHash);
        return RuntimeGuis.build(models,
                id -> id.equals(SWORD) ? Optional.of(sword) : Optional.empty(),
                id -> Optional.empty(),
                new ArrayList<>()::add);
    }

    private static CompiledGui shop(String title, int rows) {
        return new CompiledGui(SHOP, rows, "<font:demo:gui>" + title, title,
                List.of(new CompiledGui.Slot(4, SWORD, 1)));
    }

    private static CompiledGui arena() {
        return new CompiledGui(ARENA, 1, "<font:demo:gui>Arena", "Arena", List.of());
    }
}
