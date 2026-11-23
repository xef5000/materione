package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.plugin.gui.session.GuiViewerState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The memory budgets of views.md section 5.7 (plugin.md section 9): what the view model adds to one viewer stays within
 * 512 bytes, and a {@code predicate} kind's candidate record within 512 bytes. Heap deltas over many objects, after a
 * collection; the model's share of a viewer is its (empty) session instance, measured, plus the dirty bit,
 * the tick of the last automatic render and the instance id, counted at their HotSpot sizes. The values a rule
 * writes are the author's state, not the model's overhead, and the resolved keys of a definition live once in the shared
 * index, not per viewer.
 */
class ViewFootprintTest {

    private static final int BUDGET_BYTES = 512;
    private static final int COUNT = 20_000;
    private static final int VALUES = 0;
    /** An {@code AtomicBoolean} (16), the {@code long} (8) and two references (8 each), compressed oops off. */
    private static final int DIRTY_AND_CLOCK = 16 + 8 + 16;
    /** A {@code UUID} (32 on HotSpot). */
    private static final int INSTANCE_ID = 32;

    private static long used() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static List<ViewerRecord> records() {
        List<ViewerRecord> out = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            out.add(GuiViewerState.record(UUID.randomUUID(), "p", NamespacedId.parse("demo:menu"), Map.of()));
        }
        return out;
    }

    @Test
    void theModelAddsLessThanFiveHundredTwelveBytesToAViewer() {
        Map<NamespacedId, Object> values = new HashMap<>();
        for (int i = 0; i < VALUES; i++) {
            values.put(NamespacedId.of("demo", "k" + i), 0d);
        }
        List<ViewerRecord> plain = records();
        long baseline = used();
        List<ViewerRecord> admitted = records();
        SessionVariableStore store = new SessionVariableStore();
        long before = used();
        for (ViewerRecord record : admitted) {
            store.open(record);
            store.restore(record.instance(), copyOf(values));
        }
        long after = used();

        double storeBytes = (after - before) / (double) COUNT;
        double model = storeBytes + DIRTY_AND_CLOCK + INSTANCE_ID;

        assertThat(model).as("bytes added by the model per viewer").isLessThan(BUDGET_BYTES);
        assertThat(plain.size() + admitted.size() + baseline).isPositive(); // keep both lists reachable past the measurement
    }

    @Test
    void aCandidateRecordIsLessThanFiveHundredTwelveBytes() {
        ViewRegistry registry = new ViewRegistry();
        UUID[] players = new UUID[COUNT];
        for (int i = 0; i < COUNT; i++) {
            players[i] = UUID.randomUUID();
        }
        long before = used();
        for (UUID player : players) {
            registry.candidate("hud", player);
        }
        long after = used();

        double perCandidate = (after - before) / (double) COUNT;

        assertThat(perCandidate).as("bytes per candidate, its registry entry included").isLessThan(BUDGET_BYTES);
        assertThat(registry.candidateOrNull("hud", players[0])).isNotNull();
    }

    private static Map<NamespacedId, Object> copyOf(Map<NamespacedId, Object> values) {
        Map<NamespacedId, Object> copy = new HashMap<>();
        values.forEach((key, value) -> copy.put(key, Double.valueOf(((Double) value) + Math.random())));
        return copy;
    }
}
