package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.plugin.gui.session.GuiViewerState;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The write observer's cost on the writer's thread (views.md section 5.7, plugin.md section 9): a write nothing reads
 * adds one index lookup, and a write one viewer reads adds the match, the compare-and-set and the wake call. The
 * budget is the observer's own call, best of five rounds so that one scheduling hiccup on a shared CI machine does
 * not decide the test; the store behind the write is not measured.
 */
class ViewReadsIndexTest {

    /** A write that no view reads, in nanoseconds added (measured about 5 ns). */
    private static final double MISS_BUDGET_NS = 250;
    /** A write that one view reads, one viewer: match, mark dirty, wake, in nanoseconds (measured about 35 ns). */
    private static final double MATCH_BUDGET_NS = 2_000;
    private static final int DEFINITIONS = 200;
    private static final int WARMUP = 200_000;
    private static final int CALLS = 500_000;
    private static final int ROUNDS = 5;

    private static VariableDeclaration player(String key) {
        return new VariableDeclaration(NamespacedId.of("demo", key), VariableScope.PLAYER, VariableType.NUMBER, 0d, "");
    }

    /** A shadow of 200 definitions, each reading its own player variable, as a server with many menus has. */
    private static ReadsIndex realisticIndex(ViewRef reader, VariableDeclaration read) {
        var reads = new LinkedHashMap<ViewRef, ViewReads>();
        for (int i = 0; i < DEFINITIONS; i++) {
            var key = new ReadKey(VariableScope.PLAYER, NamespacedId.of("demo", "v" + i));
            reads.put(new ViewRef("gui", NamespacedId.of("demo", "menu" + i)),
                    new ViewReads(Map.of(key, EnumSet.of(ReadRole.CONTENT)), false));
        }
        reads.put(reader, new ViewReads(Map.of(new ReadKey(read.scope(), read.id()), EnumSet.of(ReadRole.CONTENT)), false));
        return ReadsIndex.of(reads);
    }

    @Test
    void aWriteNoViewReadsAddsOneIndexLookup() {
        var registry = new ViewRegistry();
        var wakes = new AtomicInteger();
        var index = realisticIndex(new ViewRef("gui", NamespacedId.of("demo", "x")), player("read"));
        var observer = new ViewChanges(() -> index, registry, new ViewKinds(), target -> wakes.incrementAndGet());
        VariableDeclaration unread = player("unread");
        UUID holder = UUID.randomUUID();

        double best = best(() -> observer.written(unread, holder));

        System.out.printf("MATERIONE-PERF view.writeObserver miss: %.1f ns (budget %.0f)%n", best, MISS_BUDGET_NS);
        assertThat(best).as("one index lookup for a write nothing reads, ns, best of %d rounds", ROUNDS)
                .isLessThan(MISS_BUDGET_NS);
        assertThat(wakes.get()).isZero();
    }

    @Test
    void aWriteOneViewerReadsIsMatchedMarkedAndWokenWithinTheBudget() {
        var registry = new ViewRegistry();
        var wakes = new AtomicInteger();
        VariableDeclaration read = player("read");
        var reader = new ViewRef("gui", NamespacedId.of("demo", "menu"));
        var index = realisticIndex(reader, read);
        var observer = new ViewChanges(() -> index, registry, new ViewKinds(), target -> wakes.incrementAndGet());
        UUID player = UUID.randomUUID();
        ViewerRecord record = GuiViewerState.record(player, "p", reader.definition(), Map.of());
        registry.begin(record);

        // each call must see a clean bit, or it would measure the coalesced path; clearing is one atomic store
        double best = best(() -> {
            record.clearDirty();
            observer.written(read, player);
        });

        System.out.printf("MATERIONE-PERF view.writeObserver one viewer: %.1f ns (budget %.0f)%n", best, MATCH_BUDGET_NS);
        assertThat(best).as("match, mark and wake one viewer, ns, best of %d rounds", ROUNDS).isLessThan(MATCH_BUDGET_NS);
        assertThat(wakes.get()).as("every measured call woke the viewer").isGreaterThanOrEqualTo(CALLS);
    }

    private static double best(Runnable call) {
        double best = Double.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < WARMUP; i++) {
                call.run();
            }
            long start = System.nanoTime();
            for (int i = 0; i < CALLS; i++) {
                call.run();
            }
            best = Math.min(best, (System.nanoTime() - start) / (double) CALLS);
        }
        return best;
    }
}
