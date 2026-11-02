package dev.materione.plugin.gui.window;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HeldTitleRefreshTest {
    @Test
    void coalescesAndReadsLatestStateAtExecution() {
        var queue = new HeldTitleRefresh();
        var session = session();
        var tasks = new ArrayList<Runnable>();
        var calls = new ArrayList<String>();
        session.vars().put("value", "old");
        queue.ensure(session, () -> calls.add(session.vars().get("value").toString()),
                (task, retired) -> { tasks.add(task); return ScheduledHandle.NONE; });
        session.vars().put("value", "latest");
        queue.ensure(session, () -> calls.add("obsolete"), (task, retired) -> {
            tasks.add(task);
            return ScheduledHandle.NONE;
        });
        assertThat(tasks).hasSize(1);
        tasks.get(0).run();
        assertThat(calls).containsExactly("latest");
    }

    @Test
    void cancellationAndRetirementNeverRunOldOrNewerTasks() {
        var queue = new HeldTitleRefresh();
        var session = session();
        var tasks = new ArrayList<Runnable>();
        var retired = new ArrayList<Runnable>();
        var calls = new AtomicInteger();
        java.util.function.BiFunction<Runnable, Runnable, ScheduledHandle> scheduler = (task, onRetired) -> {
            tasks.add(task);
            retired.add(onRetired);
            return ScheduledHandle.NONE;
        };
        queue.ensure(session, calls::incrementAndGet, scheduler);
        queue.cancel(session);
        queue.ensure(session, calls::incrementAndGet, scheduler);
        retired.get(0).run();
        tasks.get(0).run();
        assertThat(calls).hasValue(0);
        retired.get(1).run();
        tasks.get(1).run();
        assertThat(calls).hasValue(0);
        queue.ensure(session, calls::incrementAndGet, scheduler);
        tasks.get(2).run();
        assertThat(calls).hasValue(1);
    }

    private static GuiSession session() {
        return new GuiSession(UUID.randomUUID(), "Probe", NamespacedId.of("shop", "test"), Map.of());
    }
}
