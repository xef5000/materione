package dev.materione.behavior.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.Threading;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RunReadExecutorTest {
    private final ManualScheduler scheduler = new ManualScheduler();
    private final RunReadExecutor reads = new RunReadExecutor(scheduler, "player");

    @Test
    void dispatchesEachReadToItsOwnerWithoutBlockingOrReplaying() {
        var observed = new ArrayList<Threading>();
        var result = reads.on(Threading.PLAYER_REGION, () -> {
            observed.add(scheduler.current);
            return 1;
        }).thenCompose(first -> reads.on(Threading.GLOBAL, () -> {
            observed.add(scheduler.current);
            return first + 2;
        })).thenCompose(second -> reads.on(Threading.PLAYER_REGION, () -> {
            observed.add(scheduler.current);
            return second + 3;
        })).toCompletableFuture();

        assertThat(result).isNotDone();
        assertThat(observed).containsExactly(Threading.PLAYER_REGION);
        scheduler.runPending();
        assertThat(result.join()).isEqualTo(6);
        assertThat(observed).containsExactly(Threading.PLAYER_REGION, Threading.GLOBAL, Threading.PLAYER_REGION);
        assertThat(scheduler.history).extracting(ManualScheduler.Scheduled::thread)
                .containsExactly(Threading.GLOBAL, Threading.PLAYER_REGION);
    }

    @Test
    void retirementFailsWithoutInvokingTheRead() {
        var invoked = new ArrayList<String>();
        scheduler.current = Threading.GLOBAL;
        var future = reads.on(Threading.PLAYER_REGION, () -> {
            invoked.add("read");
            return 1;
        }).toCompletableFuture();
        scheduler.dropAll();
        assertThatThrownBy(future::join).hasCauseInstanceOf(ReadUnavailableException.class)
                .hasMessageContaining("retired");
        assertThat(invoked).isEmpty();
    }

    @Test
    void closingCancelsPendingAndRejectsFutureWork() {
        var invoked = new ArrayList<String>();
        var future = reads.on(Threading.GLOBAL, () -> {
            invoked.add("read");
            return 1;
        }).toCompletableFuture();
        reads.close();
        assertThatThrownBy(future::join).isInstanceOf(CancellationException.class);
        scheduler.runPending();
        assertThat(invoked).isEmpty();
        assertThatThrownBy(() -> reads.on(Threading.PLAYER_REGION, () -> 1).toCompletableFuture().join())
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void lateSchedulerCallbackCannotStartAfterCancellationEvenWhenHandleCannotStopIt() {
        var callbacks = new ArrayList<Runnable>();
        RunScheduler unstoppable = new RunScheduler() {
            @Override public boolean canRunInline(Object anchor, Threading owner) { return false; }
            @Override public Handle schedule(Object anchor, Threading owner, long delay, Runnable task, Runnable retired) {
                callbacks.add(task);
                return () -> {};
            }
        };
        var executor = new RunReadExecutor(unstoppable, "player");
        var invoked = new AtomicBoolean();
        var result = executor.on(Threading.GLOBAL, () -> {
            invoked.set(true);
            return 1;
        }).toCompletableFuture();
        executor.close();
        callbacks.get(0).run();
        assertThat(invoked).isFalse();
        assertThatThrownBy(result::join).isInstanceOf(CancellationException.class);
    }

    @Test
    void directlyCancelledFutureDoesNotRunAnUnstoppableCallback() {
        var callbacks = new ArrayList<Runnable>();
        RunScheduler unstoppable = new RunScheduler() {
            @Override public boolean canRunInline(Object anchor, Threading owner) { return false; }
            @Override public Handle schedule(Object anchor, Threading owner, long delay, Runnable task, Runnable retired) {
                callbacks.add(task);
                return () -> {};
            }
        };
        var executor = new RunReadExecutor(unstoppable, "player");
        var invoked = new AtomicBoolean();
        var result = executor.on(Threading.GLOBAL, () -> {
            invoked.set(true);
            return 1;
        }).toCompletableFuture();
        result.cancel(false);
        callbacks.get(0).run();
        assertThat(invoked).isFalse();
    }

    @Test
    void cancellationDoesNotWaitForAnAlreadyStartedOperation() throws Exception {
        var callbacks = new ArrayList<Runnable>();
        RunScheduler unstoppable = new RunScheduler() {
            @Override public boolean canRunInline(Object anchor, Threading owner) { return false; }
            @Override public Handle schedule(Object anchor, Threading owner, long delay, Runnable task, Runnable retired) {
                callbacks.add(task);
                return () -> {};
            }
        };
        var executor = new RunReadExecutor(unstoppable, "player");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var result = executor.on(Threading.GLOBAL, () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return 1;
        }).toCompletableFuture();
        Thread worker = new Thread(callbacks.get(0));
        var closed = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            executor.close();
            closed.countDown();
        });
        worker.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            closer.start();
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(result::join).isInstanceOf(CancellationException.class);
        } finally {
            release.countDown();
            worker.join(5000);
            if (closer.getState() != Thread.State.NEW) {
                closer.join(5000);
            }
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(closer.isAlive()).isFalse();
    }
}
