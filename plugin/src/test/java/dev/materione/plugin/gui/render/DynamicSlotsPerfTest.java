package dev.materione.plugin.gui.render;

import static dev.materione.plugin.gui.render.RenderFixtures.barrier;
import static dev.materione.plugin.gui.render.RenderFixtures.conditional;
import static dev.materione.plugin.gui.render.RenderFixtures.gui;
import static dev.materione.plugin.gui.render.RenderFixtures.viewer;
import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.LogicContext;
import dev.materione.plugin.gui.dynamic.CompiledItemLayer;
import dev.materione.plugin.gui.RuntimeGuis;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The refresh budget of logic.md §6.3 and plugin.md §9: 54 dynamic slots, 50 open sessions, one refresh pass
 * every 20 ticks. A pass is every session's partial render and diff; the budget is the whole pass, best of three.
 *
 * <p>What it measures: the decision (visibility, the topmost layer, templates, the diff). What it does not: building
 * the Bukkit stacks, which only a changed slot pays and which needs a server to time. The sources here return
 * barriers, so a change is a real change and nothing is cached.
 */
class DynamicSlotsPerfTest {

    /** One pass over the whole server: 50 sessions, every session's 54 slots, in milliseconds (measured 0.43 ms). */
    private static final double BUDGET_MS_PER_PASS = 2.0;
    private static final int SLOTS = 54;
    private static final int SESSIONS = 50;
    private static final int WARMUP_PASSES = 300;
    private static final int PASSES = 200;

    @Test
    void fiftyFourDynamicSlotsRefreshedForFiftySessionsEveryTwentyTicksStayWithinBudget() {
        var items = new ArrayList<CompiledItemLayer>();
        for (int slot = 0; slot < SLOTS; slot++) {
            // a condition and a source that changes with the viewer, as an authored conditional item does
            items.add(conditional("slot" + slot, slot, slot, "player.level > " + (slot % 5),
                    barrier("slot " + slot)));
        }
        var live = gui(items);
        var contexts = new ArrayList<LogicContext>();
        for (int session = 0; session < SESSIONS; session++) {
            contexts.add(viewer(session % 9));
        }
        // counts, rather than records, so that a long run does not grow the heap while it is timed
        int[] reported = {0};
        var renderer = new SlotRenderer((code, gui, layerId, message) -> reported[0]++);
        var shown = new ArrayList<dev.materione.plugin.gui.render.Rendered>(SESSIONS);
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

        System.out.printf("MATERIONE-PERF gui.refresh pass %d sessions x %d dynamic slots: %.3f ms (budget %.1f)%n",
                SESSIONS, SLOTS, best, BUDGET_MS_PER_PASS);
        assertThat(best)
                .as("one refresh pass (%d sessions x %d dynamic slots), best of three: %.3f ms",
                        SESSIONS, SLOTS, best)
                .isLessThan(BUDGET_MS_PER_PASS);
        assertThat(reported[0]).as("every barrier is reported, so the measured path includes the report")
                .isPositive();
    }

    /** One refresh: each session's partial render, diffed against what its window showed. */
    private static void runPass(RuntimeGuis.Live live, SlotRenderer renderer, List<LogicContext> contexts,
            List<dev.materione.plugin.gui.render.Rendered> shown, long ticks) {
        for (int session = 0; session < contexts.size(); session++) {
            var previous = shown.get(session);
            var next = renderer.render(live, contexts.get(session), ticks, previous);
            RenderDiff.apply(previous.slots(), next.slots(), (slot, appearance) -> { });
            shown.set(session, next);
        }
    }
}
