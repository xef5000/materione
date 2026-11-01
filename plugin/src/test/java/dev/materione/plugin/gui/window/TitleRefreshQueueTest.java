package dev.materione.plugin.gui.window;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TitleRefreshQueueTest {
    @Test
    void togglingBackBeforeDeferredCallbackCannotRestoreStaleTitle() {
        var session = new GuiSession(UUID.randomUUID(), "Probe", NamespacedId.of("shop", "test"), Map.of());
        var queue = new TitleRefreshQueue();
        var scheduled = new ArrayList<Runnable>();
        var displayed = new ArrayList<String>();
        queue.update(session, true, true, () -> displayed.add("true"),
                () -> displayed.add("stale true"), (task, retired) -> scheduled.add(task));
        queue.update(session, false, true, () -> displayed.add("false"),
                () -> displayed.add("stale false"), (task, retired) -> scheduled.add(task));
        scheduled.forEach(Runnable::run);
        assertThat(displayed).containsExactly("false");
    }

    @Test
    void onlyNewestRequestRunsAndItReadsStateWhenExecuted() {
        var session = new GuiSession(UUID.randomUUID(), "Probe", NamespacedId.of("shop", "test"), Map.of());
        var queue = new TitleRefreshQueue();
        var scheduled = new ArrayList<Runnable>();
        var displayed = new ArrayList<String>();
        session.vars().put("tab", "first");
        queue.update(session, true, true, () -> displayed.add("immediate"), () -> displayed.add("stale"),
                (task, retired) -> scheduled.add(task));
        queue.update(session, true, true, () -> displayed.add("immediate"),
                () -> displayed.add(session.vars().get("tab").toString()),
                (task, retired) -> scheduled.add(task));
        session.vars().put("tab", "latest");
        scheduled.forEach(Runnable::run);
        assertThat(displayed).containsExactly("latest");
    }

    @Test
    void retirementOfAnOlderTaskCannotCancelItsReplacement() {
        var session = new GuiSession(UUID.randomUUID(), "Probe", NamespacedId.of("shop", "test"), Map.of());
        var queue = new TitleRefreshQueue();
        var tasks = new ArrayList<Runnable>();
        var retired = new ArrayList<Runnable>();
        var displayed = new ArrayList<String>();
        queue.update(session, true, true, () -> { }, () -> displayed.add("old"),
                (task, onRetired) -> { tasks.add(task); retired.add(onRetired); });
        queue.update(session, true, true, () -> { }, () -> displayed.add("new"),
                (task, onRetired) -> { tasks.add(task); retired.add(onRetired); });
        retired.get(0).run();
        tasks.forEach(Runnable::run);
        assertThat(displayed).containsExactly("new");
    }
}
