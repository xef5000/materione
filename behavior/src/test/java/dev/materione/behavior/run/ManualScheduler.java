package dev.materione.behavior.run;

import dev.materione.api.logic.Threading;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A {@link RunScheduler} driven by hand: time moves only when a test says so, and "which thread
 * am I on" is a field. It models the one thing the interpreter cares about: a task scheduled for the
 * player's thread or the global thread runs <em>there</em>, and never inline from the scheduling call.
 */
public final class ManualScheduler implements RunScheduler {

    /** One scheduled hop or wait. */
    public record Scheduled(Object anchor, Threading thread, long delay, long due) {}

    private static final class Task {
        final Scheduled info;
        final Runnable task;
        final Runnable dropped;
        boolean cancelled;

        Task(Scheduled info, Runnable task, Runnable dropped) {
            this.info = info;
            this.task = task;
            this.dropped = dropped;
        }
    }

    private final List<Task> queue = new ArrayList<>();
    /** Everything ever scheduled, in order (a test asserts on the hops a flow needed). */
    public final List<Scheduled> history = new ArrayList<>();
    /** The thread the test is "on"; the player's own thread unless a scheduled task is running. */
    public Threading current = Threading.PLAYER_REGION;
    public long now;

    @Override
    public boolean canRunInline(Object anchor, Threading thread) {
        return thread == Threading.ANY || thread == current;
    }

    @Override
    public Handle schedule(Object anchor, Threading thread, long delayTicks, Runnable task, Runnable dropped) {
        var info = new Scheduled(anchor, thread, delayTicks, now + delayTicks);
        history.add(info);
        var entry = new Task(info, task, dropped);
        queue.add(entry);
        return () -> entry.cancelled = true;
    }

    /** Tasks waiting (not cancelled). */
    public int pending() {
        return (int) queue.stream().filter(task -> !task.cancelled).count();
    }

    /** Runs every task due now (delay 0 included), and anything those schedule that is also due. */
    public void runPending() {
        boolean ran = true;
        while (ran) {
            ran = false;
            Iterator<Task> it = queue.iterator();
            Task due = null;
            while (it.hasNext()) {
                Task next = it.next();
                if (next.cancelled) {
                    it.remove();
                } else if (next.info.due() <= now) {
                    due = next;
                    it.remove();
                    break;
                }
            }
            if (due != null) {
                run(due);
                ran = true;
            }
        }
    }

    /** Moves time forward one tick at a time, running what comes due. */
    public void advance(long ticks) {
        runPending();
        for (long i = 0; i < ticks; i++) {
            now++;
            runPending();
        }
    }

    /** The owner left: every queued task is dropped instead of run. */
    public void dropAll() {
        var snapshot = List.copyOf(queue);
        queue.clear();
        snapshot.stream().filter(task -> !task.cancelled).forEach(task -> task.dropped.run());
    }

    private void run(Task task) {
        Threading before = current;
        current = task.info.thread();
        try {
            task.task.run();
        } finally {
            current = before;
        }
    }
}
