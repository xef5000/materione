package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** {@code /materione doctor} names the definitions with the most automatic re-renders per second (views.md section 5.7). */
class ViewRenderStatsTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final ViewRenderStats stats = new ViewRenderStats(clock::get);

    @Test
    void definitionsAreListedByRatePerSecondHighestFirst() {
        NamespacedId calm = NamespacedId.parse("demo:calm");
        NamespacedId busy = NamespacedId.parse("demo:busy");
        for (int i = 0; i < 2; i++) {
            stats.rendered("gui", calm);
        }
        for (int i = 0; i < 40; i++) {
            stats.rendered("gui", busy);
        }
        clock.addAndGet(10_000);

        assertThat(stats.lines(5)).containsExactly(
                "gui demo:busy: 4.00 re-renders/s (40)", "gui demo:calm: 0.20 re-renders/s (2)");
        assertThat(stats.lines(1)).hasSize(1);
    }

    @Test
    void nothingRenderedListsNothing() {
        assertThat(stats.lines(5)).isEmpty();
    }

    @Test
    void theWindowRestartsAfterFiveMinutes() {
        stats.rendered("gui", NamespacedId.parse("demo:old"));
        clock.addAndGet(301_000);
        stats.rendered("gui", NamespacedId.parse("demo:new"));
        clock.addAndGet(1_000);

        assertThat(stats.top(5)).extracting(row -> row.view().definition().toString()).containsExactly("demo:new");
    }
}
