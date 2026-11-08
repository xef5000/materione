package dev.materione.plugin.gui.render;

import dev.materione.content.gui.GuiContainers;
import static dev.materione.plugin.gui.render.RenderFixtures.template;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.ItemResolution;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.flow.Condition;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.content.gui.ItemComponent;
import dev.materione.content.gui.list.ListSort;
import dev.materione.content.gui.logic.ClickMap;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.dynamic.CompiledItemLayer;
import dev.materione.plugin.gui.dynamic.ItemBinding;
import dev.materione.plugin.gui.dynamic.ItemLayerCompiler;
import dev.materione.plugin.gui.input.InputSlots;
import dev.materione.plugin.gui.list.CompiledListLayer;
import dev.materione.plugin.gui.list.ListBinding;
import dev.materione.plugin.gui.list.ListBinding.ListRead;
import dev.materione.plugin.gui.list.ListPage;
import dev.materione.plugin.gui.rules.GuiRuleSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The list component's decision on a render (logic.md §6.7): the page's entries in the list's slots, the filter and
 * the sort before paging, the entry each slot shows, cached sources, failed reads and the layer order that lets an
 * item above a list keep its slot.
 */
class SlotRendererListTest {

    private static final Map<String, Object> ADA = row("Ada", 3);
    private static final Map<String, Object> BOB = row("Bob", 1);
    private static final Map<String, Object> CY = row("Cy", 5);
    private static final Map<String, Object> DEE = row("Dee", 2);
    private static final Map<String, Object> EVE = row("Eve", 4);
    private static final List<Map<String, Object>> FIVE = List.of(ADA, BOB, CY, DEE, EVE);

    private static Map<String, Object> row(String name, int level) {
        var row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("level", level);
        return row;
    }

    /** An entry whose item reads the entry's name, so the barrier it shows names the entry. */
    private static CompiledItemLayer entryTemplate(String layerId) {
        ItemBinding byName = context -> ItemResolution.unresolved("row " + context.value("entry.name"));
        return new CompiledItemLayer(layerId, 0, 0, null, byName, null, 0, null, List.of(),
                ItemComponent.LoreMode.REPLACE, template("1"), null, true);
    }

    private static CompiledListLayer list(String id, int order, List<Integer> slots, ListBinding source,
            boolean cached, Condition filter, ListSort sort) {
        return new CompiledListLayer(id, order, slots, source, cached, filter, sort, entryTemplate(id), null);
    }

    private static CompiledListLayer players(List<Integer> slots) {
        return list("players", 0, slots, ctx -> ListRead.ok(FIVE), false, null, null);
    }

    private static ListBinding fixed(List<Map<String, Object>> rows) {
        return ctx -> ListRead.ok(rows);
    }

    private static RuntimeGuis.Live live(List<CompiledItemLayer> items, List<CompiledListLayer> lists) {
        return new RuntimeGuis.Live(RenderFixtures.GUI, GuiContainers.byId("chest_3").orElseThrow(), "Shop", "Shop", List.of(), ClickMap.EMPTY,
                GuiRuleSet.EMPTY, InputSlots.NONE, null, List.of(), 0, items, null, lists);
    }

    /** A window's context: the viewer, and the page state the session holds for each list. */
    private static LogicContext window(Map<String, Object> pages) {
        var root = new LinkedHashMap<String, Object>();
        root.put("player", new LinkedHashMap<>(Map.of("name", "Probe", "level", 3)));
        root.put("vars", new LinkedHashMap<String, Object>());
        root.put("server", new LinkedHashMap<String, Object>());
        var gui = new LinkedHashMap<String, Object>();
        gui.put("page", new LinkedHashMap<>(pages));
        root.put("gui", gui);
        return new MapLogicContext(root);
    }

    private static String shownName(Rendered rendered, int slot) {
        return ((SlotSource.Barrier) rendered.slots().get(slot).source()).reason().replace("row ", "");
    }

    @Test
    void theFirstPageFillsTheListsSlotsInEntryOrderAndTheRestOfTheSlotsStayEmpty() {
        var reports = new RenderFixtures.Reports();
        var gui = live(List.of(), List.of(players(List.of(9, 10, 11))));

        Rendered rendered = new SlotRenderer(reports).render(gui, window(Map.of()), 0, null);

        assertThat(rendered.slots().keySet()).containsExactlyInAnyOrder(9, 10, 11);
        assertThat(shownName(rendered, 9)).isEqualTo("Ada");
        assertThat(shownName(rendered, 10)).isEqualTo("Bob");
        assertThat(shownName(rendered, 11)).isEqualTo("Cy");
        assertThat(rendered.lists().pages()).containsEntry("players", new ListPage(1, 2, 5));
        assertThat(rendered.lists().shownAt(10).orElseThrow().entry()).containsEntry("name", "Bob");
        // each entry's item is an unresolved source here, which is reported (MTR-0072) and shown as a barrier
        assertThat(reports.codes).containsOnly("MTR-0072");
    }

    @Test
    void thePageTheSessionShowsIsTheOneRenderedAndAPageBeyondTheListIsClamped() {
        var gui = live(List.of(), List.of(players(List.of(9, 10, 11))));
        var renderer = new SlotRenderer(new RenderFixtures.Reports());

        Rendered second = renderer.render(gui, window(Map.of("players", Map.of("number", 2, "count", 2))), 0, null);
        assertThat(second.slots().keySet()).containsExactlyInAnyOrder(9, 10);
        assertThat(shownName(second, 9)).isEqualTo("Dee");
        assertThat(shownName(second, 10)).isEqualTo("Eve");
        assertThat(second.lists().pages()).containsEntry("players", new ListPage(2, 2, 5));

        Rendered clamped = renderer.render(gui, window(Map.of("players", Map.of("number", 9, "count", 2))), 0, null);
        assertThat(clamped.lists().pages()).containsEntry("players", new ListPage(2, 2, 5));
        assertThat(clamped.slots().keySet()).containsExactlyInAnyOrder(9, 10);
    }

    @Test
    void aFilterKeepsTheEntriesThatShowAndTheSortOrdersThemBeforePaging() {
        Condition overOne = FlowCompiler.compileCondition(RenderFixtures.GUI, "filter", "${entry.level > 1}",
                ItemLayerCompiler.ENTRY_ROOTS, RenderFixtures.lookup()).condition().orElseThrow();
        var gui = live(List.of(), List.of(list("players", 0, List.of(9, 10, 11), fixed(FIVE), false, overOne,
                new ListSort("name", true))));

        Rendered rendered = new SlotRenderer(new RenderFixtures.Reports()).render(gui, window(Map.of()), 0, null);

        // Bob (level 1) is filtered out; the four others sort by name, descending, before the page is cut
        assertThat(shownName(rendered, 9)).isEqualTo("Eve");
        assertThat(shownName(rendered, 10)).isEqualTo("Dee");
        assertThat(shownName(rendered, 11)).isEqualTo("Cy");
        assertThat(rendered.lists().pages()).containsEntry("players", new ListPage(1, 2, 4));
    }

    @Test
    void aCachedSourceIsReadOnlyOnAFullRenderAndKeepsItsSnapshotBetweenTicksAndPages() {
        var reads = new AtomicInteger();
        ListBinding counted = ctx -> {
            reads.incrementAndGet();
            return ListRead.ok(FIVE);
        };
        var gui = live(List.of(), List.of(list("players", 0, List.of(9, 10, 11), counted, true, null, null)));
        var renderer = new SlotRenderer(new RenderFixtures.Reports());

        Rendered opened = renderer.render(gui, window(Map.of()), 0, null);
        assertThat(reads.get()).isEqualTo(1);
        assertThat(opened.lists().entries().get("players")).containsExactlyElementsOf(FIVE);

        Rendered tick = renderer.render(gui, window(Map.of()), 20, opened);
        Rendered page = renderer.render(gui, window(Map.of("players", Map.of("number", 2, "count", 2))), 20, tick);
        assertThat(reads.get()).as("a tick and a page change read the snapshot, not the source").isEqualTo(1);
        assertThat(shownName(page, 9)).isEqualTo("Dee");

        renderer.render(gui, window(Map.of()), 0, null);
        assertThat(reads.get()).as("a full render (open, gui.refresh) reads the source again").isEqualTo(2);
    }

    @Test
    void anUncachedSourceIsReadOnEveryRender() {
        var reads = new AtomicInteger();
        ListBinding counted = ctx -> {
            reads.incrementAndGet();
            return ListRead.ok(FIVE);
        };
        var gui = live(List.of(), List.of(list("players", 0, List.of(9), counted, false, null, null)));
        var renderer = new SlotRenderer(new RenderFixtures.Reports());

        Rendered first = renderer.render(gui, window(Map.of()), 0, null);
        renderer.render(gui, window(Map.of()), 20, first);
        assertThat(reads.get()).isEqualTo(2);
    }

    @Test
    void aSourceThatCannotReadShowsNoEntriesAndIsReported() {
        var reports = new RenderFixtures.Reports();
        var gui = live(List.of(), List.of(list("players", 0, List.of(9, 10),
                ctx -> ListRead.failed("the variable is not declared"), false, null, null)));

        Rendered rendered = new SlotRenderer(reports).render(gui, window(Map.of()), 0, null);

        assertThat(rendered.slots()).isEmpty();
        assertThat(reports.codes).containsExactly("MTR-0079");
        assertThat(reports.messages.get(0)).contains("the variable is not declared");
    }

    @Test
    void anItemAboveTheListKeepsItsSlotAndTheEntryThereIsNotShown() {
        ItemBinding barrier = ctx -> ItemResolution.unresolved("item");
        var above = new CompiledItemLayer("frame", 5, 10, null, barrier, null, 0, null, List.of(),
                ItemComponent.LoreMode.REPLACE, template("1"), null, false);
        var gui = live(List.of(above), List.of(players(List.of(9, 10, 11))));

        Rendered rendered = new SlotRenderer(new RenderFixtures.Reports()).render(gui, window(Map.of()), 0, null);

        assertThat(((SlotSource.Barrier) rendered.slots().get(10).source()).reason()).isEqualTo("item");
        assertThat(rendered.lists().shownAt(10)).isEmpty();
        assertThat(shownName(rendered, 9)).isEqualTo("Ada");
        assertThat(shownName(rendered, 11)).isEqualTo("Cy");
    }

    @Test
    void theShownEntryCarriesItsFieldsForTheClickRouter() {
        var gui = live(List.of(), List.of(players(List.of(9))));
        Rendered rendered = new SlotRenderer(new RenderFixtures.Reports()).render(gui, window(Map.of()), 0, null);

        assertThat(rendered.lists().shownAt(9).orElseThrow().layer()).isEqualTo("players");
        assertThat(rendered.lists().shownAt(9).orElseThrow().entry()).containsEntry("level", 3);
    }
}
