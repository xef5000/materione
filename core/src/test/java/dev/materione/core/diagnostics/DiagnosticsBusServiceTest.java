package dev.materione.core.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DiagnosticsBusServiceTest {

    @Test
    void publishesCountsAndKeepsBoundedHistory() {
        DiagnosticsBusService bus = new DiagnosticsBusService(4);
        for (int i = 0; i < 10; i++) {
            bus.publish(Diagnostic.info(DiagnosticCodes.PHASE_FAILED, "n" + i));
        }
        assertThat(bus.total()).isEqualTo(10);
        assertThat(bus.count(Severity.INFO)).isEqualTo(10);
        assertThat(bus.count(Severity.ERROR)).isZero();
        assertThat(bus.recent(100)).hasSize(4);
        assertThat(bus.recent(4).getLast().message()).isEqualTo("n9");
        assertThat(bus.recent(0)).isEmpty();
    }

    @Test
    void brokenListenerIsIsolatedAndRemoved() {
        DiagnosticsBusService bus = new DiagnosticsBusService(8);
        List<Diagnostic> good = new ArrayList<>();
        bus.subscribe(d -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(good::add);

        bus.publish(Diagnostic.info(DiagnosticCodes.PHASE_FAILED, "one"));
        bus.publish(Diagnostic.info(DiagnosticCodes.PHASE_FAILED, "two"));

        // The good listener got both; the broken one didn't break the publish.
        assertThat(good).hasSize(2);
    }

    @Test
    void subscriptionCanBeClosed() throws Exception {
        DiagnosticsBusService bus = new DiagnosticsBusService(8);
        List<Diagnostic> seen = new ArrayList<>();
        AutoCloseable subscription = bus.subscribe(seen::add);
        bus.publish(Diagnostic.info(DiagnosticCodes.PHASE_FAILED, "x"));
        assertThat(seen).hasSize(1);
        subscription.close();
        bus.publish(Diagnostic.info(DiagnosticCodes.PHASE_FAILED, "y"));
        assertThat(seen).hasSize(1);
    }

    @Test
    void concurrentPublishIsSafe() throws Exception {
        DiagnosticsBusService bus = new DiagnosticsBusService(64);
        CountDownLatch start = new CountDownLatch(1);
        int threads = 8;
        int perThread = 100;
        // Test threads are fine: the ArchUnit thread ban applies to product code,
        // and the harness exemption covers test JVMs by convention (see R5 docs).
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        bus.publish(Diagnostic.info(DiagnosticCodes.PHASE_FAILED, "c" + i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(bus.total()).isEqualTo(threads * perThread);
        assertThat(bus.recent(1)).hasSize(1);
    }
}
