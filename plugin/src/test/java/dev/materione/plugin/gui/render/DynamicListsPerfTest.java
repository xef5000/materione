package dev.materione.plugin.gui.render;

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
import dev.materione.plugin.gui.rules.GuiRuleSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The refresh budget of a list (logic.md §6.7, plugin.md §9): a 45-slot list, one of 50 open sessions, refreshed every
 * 20 ticks with its filter and sort, and every entry's item re-read. The pass is every session's partial render and
 * diff; the budget is the whole pass, best of three.
 *
 * <p>The source here is uncached, the worst case: it returns its entries on every render. A cached source costs less,
 * as it skips the read. The sources return barriers, so no Bukkit stack is built, which only a changed slot pays.
 */
class DynamicListsPerfTest {

    /** One pass over the whole server, in milliseconds (measured in {@link #BUDGET_NOTE}). */
    private static final double BUDGET_MS_PER_PASS = 2.0;
    private static final String BUDGET_NOTE = "a 45-slot list in 50 windows, filtered and sorted, every 20 ticks";
    private static final int SLOTS = 45;
    private static final int SESSIONS = 50;
    private static final int WARMUP_PASSES = 300;
    private static final int PASSES = 200;

    @Test
    void aFortyFiveSlotListRefreshedForFiftySessionsEveryTwentyTicksStaysWithinBudget() {
        var entries = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < SLOTS; index++) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", "player" + index);
            entry.put("level", index % 7);
            entries.add(entry);
        }
        List<Map<String, Object>> rows = List.copyOf(entries);
        Condition aboveZero = FlowCompiler.compileCondition(RenderFixtures.GUI, "filter", "${entry.level > 0}",
                ItemLayerCompiler.ENTRY_ROOTS, RenderFixtures.lookup()).condition().orElseThrow();
        ItemBinding byName = context -> ItemResolution.unresolved("row " + context.value("entry.name"));
        var entry = new CompiledItemLayer("players", 0, 0, null, byName, null, 0, null, List.of(),
                ItemComponent.LoreMode.REPLACE, template("1"), null, true);
        ListBinding source = ctx -> ListBinding.ListRead.ok(rows);
        var list = new CompiledListLayer("players", 0, slotsOf(SLOTS), source, false, aboveZero,
                new ListSort("name", false), entry, null);
        var live = new RuntimeGuis.Live(RenderFixtures.GUI, 6, "Roster", "Roster", List.of(), ClickMap.EMPTY,
                GuiRuleSet.EMPTY, InputSlots.NONE, null, List.of(), 0, List.of(), null, List.of(list));

        var contexts = new ArrayList<LogicContext>();
        for (int session = 0; session < SESSIONS; session++) {
            contexts.add(window(session));
        }
        // counts, rather than records, so that a long run does not grow the heap while it is timed
        int[] reported = {0};
        var renderer = new SlotRenderer((code, gui, layerId, message) -> reported[0]++);
        var shown = new ArrayList<Rendered>(SESSIONS);
        for (LogicContext context : contexts) {
            shown.add(renderer.render(live, context, 0, null));
        }

        double best = Double.MAX_VALUE;
        for (int attempt = 0; attempt < 3; attempt++) {
            long ticks = 0;
            for (int pass = 0; pass < WARMUP_PASSES; pass++) {
                ticks += 20;
                runPass(live, renderer, contexts, shown, ticks);
            }
            long start = System.nanoTime();
            for (int pass = 0; pass < PASSES; pass++) {
                ticks += 20;
                runPass(live, renderer, contexts, shown, ticks);
            }
            double perPass = (System.nanoTime() - start) / 1e6 / PASSES;
            best = Math.min(best, perPass);
        }

        System.out.printf("MATERIONE-PERF gui.list refresh pass %d sessions x %d list slots (%s): %.3f ms (budget %.1f)%n",
                SESSIONS, SLOTS, BUDGET_NOTE, best, BUDGET_MS_PER_PASS);
        assertThat(best)
                .as("one list refresh pass (%d sessions x %d slots), best of three: %.3f ms", SESSIONS, SLOTS, best)
                .isLessThan(BUDGET_MS_PER_PASS);
        // the filter drops the seven entries of level 0, so the window's first page shows the other 38 entries
        assertThat(shown.get(0).slots()).as("the filtered entries fill the first page").hasSize(SLOTS - 7);
        assertThat(reported[0]).as("every item barrier is reported, so the measured path includes the report")
                .isPositive();
    }

    private static List<Integer> slotsOf(int count) {
        var slots = new ArrayList<Integer>(count);
        for (int slot = 0; slot < count; slot++) {
            slots.add(slot);
        }
        return slots;
    }

    /** A viewer at one of a few levels, whose window holds a page of the list with its own page state. */
    private static LogicContext window(int session) {
        var root = new LinkedHashMap<String, Object>();
        root.put("player", new LinkedHashMap<>(Map.of("name", "Probe" + session, "level", session % 9)));
        root.put("vars", new LinkedHashMap<String, Object>());
        root.put("server", new LinkedHashMap<String, Object>());
        root.put("gui", new LinkedHashMap<>(Map.of("page", Map.of("players", Map.of("number", 1, "count", 1)))));
        return new MapLogicContext(root);
    }

    /** One refresh: each session's partial render, diffed against what its window showed. */
    private static void runPass(RuntimeGuis.Live live, SlotRenderer renderer, List<LogicContext> contexts,
            List<Rendered> shown, long ticks) {
        for (int session = 0; session < contexts.size(); session++) {
            var previous = shown.get(session);
            var next = renderer.render(live, contexts.get(session), ticks, previous);
            RenderDiff.apply(previous.slots(), next.slots(), (slot, appearance) -> { });
            shown.set(session, next);
        }
    }
}
