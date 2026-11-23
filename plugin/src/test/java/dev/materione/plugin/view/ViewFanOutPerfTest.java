package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.plugin.gui.session.GuiViewerState;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Fan-out (views.md section 5.7, plugin.md section 9): a {@code server} variable read by one definition that 200
 * players have open is marked on the writer's thread within 100 microseconds, 0.5 microsecond per viewer. Best of five
 * rounds, as {@link ViewReadsIndexTest}; the clean-up between writes (the render's clear) is inside the measured loop,
 * so the number is the pessimistic one.
 */
class ViewFanOutPerfTest {

    /** One server write with 200 viewers, in microseconds (measured about 3 us). */
    private static final double BUDGET_US = 100;
    private static final int VIEWERS = 200;
    private static final int WARMUP = 2_000;
    private static final int WRITES = 5_000;
    private static final int ROUNDS = 5;

    @Test
    void aServerWriteReadByTwoHundredViewersStaysWithinBudget() {
        var registry = new ViewRegistry();
        var view = new ViewRef("gui", NamespacedId.of("demo", "menu"));
        var read = new VariableDeclaration(
                NamespacedId.of("demo", "season"), VariableScope.SERVER, VariableType.NUMBER, 0d, "");
        var index = ReadsIndex.of(Map.of(view, new ViewReads(
                Map.of(new ReadKey(VariableScope.SERVER, read.id()), EnumSet.of(ReadRole.CONTENT)), false)));
        var wakes = new AtomicInteger();
        var observer = new ViewChanges(() -> index, registry, new ViewKinds(), target -> wakes.incrementAndGet());
        List<ViewerRecord> records = new ArrayList<>();
        for (int i = 0; i < VIEWERS; i++) {
            ViewerRecord record = GuiViewerState.record(UUID.randomUUID(), "p" + i, view.definition(), Map.of());
            registry.begin(record);
            records.add(record);
        }

        double best = Double.MAX_VALUE;
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < WARMUP; i++) {
                write(observer, read, records);
            }
            long start = System.nanoTime();
            for (int i = 0; i < WRITES; i++) {
                write(observer, read, records);
            }
            best = Math.min(best, (System.nanoTime() - start) / 1e3 / WRITES);
        }

        System.out.printf("MATERIONE-PERF view.fanOut server write, %d viewers: %.1f us (budget %.0f)%n",
                VIEWERS, best, BUDGET_US);
        assertThat(best).as("one server write, %d viewers, microseconds, best of %d rounds", VIEWERS, ROUNDS)
                .isLessThan(BUDGET_US);
        assertThat(wakes.get()).as("every viewer was marked each time").isEqualTo(ROUNDS * (WARMUP + WRITES) * VIEWERS);
    }

    private static void write(ViewChanges observer, VariableDeclaration read, List<ViewerRecord> records) {
        for (ViewerRecord record : records) {
            record.clearDirty();
        }
        observer.written(read, null);
    }
}
