package dev.materione.plugin.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.content.gui.CompiledGui;
import dev.materione.core.runtime.RuntimeItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The live GUI view (gui.md §5): slot items resolve at apply time, a missing item is one
 * diagnostic and an empty slot, and the view says exactly when a viewer's window is stale.
 */
class RuntimeGuisTest {

    private static final NamespacedId SHOP = NamespacedId.parse("demo:shop");
    private static final NamespacedId SWORD = NamespacedId.parse("demo:sword");

    @Test
    void resolvesAMaterioneItemAndKeepsTheRowsAndTitle() {
        RuntimeItem sword = item(SWORD, "hash-1");
        RuntimeGuis view = build(
                Map.of(SHOP, shop(slot(13, SWORD, 1))),
                id -> id.equals(SWORD) ? Optional.of(sword) : Optional.empty(),
                new ArrayList<>());

        RuntimeGuis.Live live = view.gui(SHOP).orElseThrow();
        assertThat(live.rows()).isEqualTo(3);
        assertThat(live.title()).isEqualTo("<font:demo:gui>Shop");
        assertThat(live.accessibleTitle()).isEqualTo("Shop");
        assertThat(live.slots()).containsExactly(
                new RuntimeGuis.Slot(13, new RuntimeGuis.Item.Materione(sword), 1));
    }

    @Test
    void aMissingItemIsDiagnosedAndItsSlotStaysEmptyWhileTheRestShows() {
        RuntimeItem sword = item(SWORD, "hash-1");
        List<Diagnostic> diagnostics = new ArrayList<>();
        RuntimeGuis view = build(
                Map.of(SHOP, shop(slot(0, NamespacedId.parse("demo:ghost"), 1), slot(4, SWORD, 2))),
                id -> id.equals(SWORD) ? Optional.of(sword) : Optional.empty(),
                diagnostics);

        RuntimeGuis.Live live = view.gui(SHOP).orElseThrow();
        assertThat(live.slots()).extracting(RuntimeGuis.Slot::slot).containsExactly(4);
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo(DiagnosticCodes.GUI_SLOT_ITEM_UNRESOLVED);
        assertThat(diagnostics.get(0).message())
                .contains("slot 0")
                .contains("demo:ghost")
                .contains("the slot stays empty");
    }

    @Test
    void aMinecraftReferenceThatIsNoMaterialIsDiagnosedNotGuessed() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        RuntimeGuis view = build(
                Map.of(SHOP, shop(slot(2, NamespacedId.parse("minecraft:not_a_thing"), 1))),
                id -> Optional.empty(),
                diagnostics);

        assertThat(view.gui(SHOP).orElseThrow().slots()).isEmpty();
        assertThat(diagnostics).extracting(Diagnostic::code).containsExactly(DiagnosticCodes.GUI_SLOT_ITEM_UNRESOLVED);
    }

    @Test
    void anUnknownGuiIsNotLiveAndAnEmptyViewHoldsNothing() {
        RuntimeGuis view = build(Map.of(SHOP, shop()), id -> Optional.empty(), new ArrayList<>());

        assertThat(view.gui(NamespacedId.parse("demo:other"))).isEmpty();
        assertThat(view.all()).hasSize(1);
        assertThat(RuntimeGuis.empty().isEmpty()).isTrue();
        assertThat(RuntimeGuis.empty().gui(SHOP)).isEmpty();
    }

    @Test
    void twoViewsOfTheSameContentAreTheSameWindowEvenWhenTheItemObjectIsRebuilt() {
        var before = build(Map.of(SHOP, shop(slot(1, SWORD, 1))),
                id -> Optional.of(item(SWORD, "hash-1")), new ArrayList<>()).gui(SHOP).orElseThrow();
        var after = build(Map.of(SHOP, shop(slot(1, SWORD, 1))),
                id -> Optional.of(item(SWORD, "hash-1")), new ArrayList<>()).gui(SHOP).orElseThrow();

        assertThat(after.sameAs(before)).isTrue();
    }

    @Test
    void aChangeToTitleRowsSlotItemOrAmountIsADifferentWindow() {
        var base = build(Map.of(SHOP, shop(slot(1, SWORD, 1))),
                id -> Optional.of(item(SWORD, "hash-1")), new ArrayList<>()).gui(SHOP).orElseThrow();

        var retitled = build(Map.of(SHOP, new CompiledGui(SHOP, 3, "<font:demo:gui>Market", "Market",
                List.of(slot(1, SWORD, 1)))), id -> Optional.of(item(SWORD, "hash-1")), new ArrayList<>());
        var resized = build(Map.of(SHOP, new CompiledGui(SHOP, 4, base.title(), "Shop",
                List.of(slot(1, SWORD, 1)))), id -> Optional.of(item(SWORD, "hash-1")), new ArrayList<>());
        var reworked = build(Map.of(SHOP, shop(slot(1, SWORD, 1))),
                id -> Optional.of(item(SWORD, "hash-2")), new ArrayList<>());
        var recounted = build(Map.of(SHOP, shop(slot(1, SWORD, 3))),
                id -> Optional.of(item(SWORD, "hash-1")), new ArrayList<>());

        assertThat(retitled.gui(SHOP).orElseThrow().sameAs(base)).isFalse();
        assertThat(resized.gui(SHOP).orElseThrow().sameAs(base)).isFalse();
        assertThat(reworked.gui(SHOP).orElseThrow().sameAs(base)).isFalse();
        assertThat(recounted.gui(SHOP).orElseThrow().sameAs(base)).isFalse();
    }

    // --- fixtures -----------------------------------------------------------

    private static RuntimeGuis build(
            Map<NamespacedId, CompiledGui> models,
            java.util.function.Function<NamespacedId, Optional<RuntimeItem>> items,
            List<Diagnostic> sink) {
        // no vanilla lookup: a server-less test never resolves a material
        return RuntimeGuis.build(models, items, id -> Optional.empty(), sink::add);
    }

    private static CompiledGui shop(CompiledGui.Slot... slots) {
        return new CompiledGui(SHOP, 3, "<font:demo:gui>Shop", "Shop", List.of(slots));
    }

    private static CompiledGui.Slot slot(int slot, NamespacedId item, int amount) {
        return new CompiledGui.Slot(slot, item, amount);
    }

    private static RuntimeItem item(NamespacedId id, String definitionHash) {
        return new RuntimeItem(id, "PAPER", null, "Sword", List.of(), definitionHash);
    }
}
