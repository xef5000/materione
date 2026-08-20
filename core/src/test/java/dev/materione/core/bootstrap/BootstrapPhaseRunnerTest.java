package dev.materione.core.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BootstrapPhaseRunnerTest {

    private final List<String> log = new ArrayList<>();
    private final BootstrapPhaseRunner runner = new BootstrapPhaseRunner(log::add);

    @Test
    void runsPhasesInOrderAndRecordsTiming() {
        List<String> order = new ArrayList<>();
        var result = runner.run(List.of(
                BootstrapPhase.degrade("a", () -> order.add("a")),
                BootstrapPhase.degrade("b", () -> order.add("b"))));

        assertThat(order).containsExactly("a", "b");
        assertThat(result.disabled()).isFalse();
        assertThat(result.failures()).isZero();
        assertThat(result.phases()).hasSize(2);
        assertThat(result.phases().getFirst().name()).isEqualTo("a");
        assertThat(result.phases().getFirst().ok()).isTrue();
        assertThat(result.phases().getFirst().durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void degradePolicyContinuesAfterFailure() {
        var result = runner.run(List.of(
                BootstrapPhase.degrade("boom", () -> {
                    throw new IllegalStateException("nope");
                }),
                BootstrapPhase.degrade("after", () -> log.add("after ran"))));

        assertThat(result.disabled()).isFalse();
        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.phases().getFirst().ok()).isFalse();
        assertThat(result.phases().getFirst().error()).contains("IllegalStateException").contains("nope");
        assertThat(log).anySatisfy(line -> assertThat(line).contains("phase 'boom' failed"));
        assertThat(log).contains("after ran");
    }

    @Test
    void disablePolicyStopsTheRun() {
        List<String> order = new ArrayList<>();
        var result = runner.run(List.of(
                BootstrapPhase.degrade("first", () -> order.add("first")),
                BootstrapPhase.disable("fatal", () -> {
                    throw new UnsupportedOperationException("floor");
                }),
                BootstrapPhase.degrade("never", () -> order.add("never"))));

        assertThat(result.disabled()).isTrue();
        assertThat(result.failures()).isEqualTo(1);
        assertThat(order).containsExactly("first");
        assertThat(result.phases()).hasSize(2); // 'never' never ran
    }

    @Test
    void errorReportingSurvivesNullMessage() {
        var result = runner.run(List.of(
                BootstrapPhase.disable("weird", () -> {
                    throw new RuntimeException();
                })));
        assertThat(result.phases().getFirst().error()).isNotNull();
    }

    @Test
    void shutdownRunsReverseIsolatedAndNeverThrows() {
        List<String> order = new ArrayList<>();
        AtomicInteger abandoned = new AtomicInteger();
        runner.shutdownReverse(
                List.of("one", "two", "three"),
                List.<Runnable>of(
                        () -> order.add("one"),
                        () -> {
                            throw new RuntimeException("shutdown explode");
                        },
                        () -> order.add("three")),
                Duration.ofSeconds(5));

        // Reverse order; the exploding step did not stop the rest.
        assertThat(order).containsExactly("three", "one");
        assertThat(abandoned.get()).isZero();
    }

    @Test
    void shutdownAbandonsRemainingStepsPastDeadline() {
        List<String> order = new ArrayList<>();
        // Reverse order runs "slow" (index 1) first; it sleeps past the
        // deadline, so "skipped" (index 0) is abandoned before it runs.
        runner.shutdownReverse(
                List.of("skipped", "slow"),
                List.<Runnable>of(
                        () -> order.add("skipped"),
                        () -> {
                            order.add("slow");
                            try {
                                Thread.sleep(150);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }),
                Duration.ofMillis(30));

        assertThat(order).containsExactly("slow");
    }
}
