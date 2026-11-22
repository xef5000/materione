package dev.materione.plugin.gui.window;

import dev.materione.plugin.view.ViewerRecord;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** Cancels superseded deferred window refreshes without retaining completed sessions. */
final class TitleRefreshQueue {
    private final ConcurrentHashMap<ViewerRecord, Long> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    void invalidate(ViewerRecord session) {
        pending.remove(session);
    }

    void update(ViewerRecord session, boolean requiresDeferral, boolean dispatching, Runnable apply, Runnable refresh,
            BiConsumer<Runnable, Runnable> schedule) {
        update(session, requiresDeferral, dispatching, apply, refresh, schedule, () -> { });
    }

    void update(ViewerRecord session, boolean requiresDeferral, boolean dispatching, Runnable apply, Runnable refresh,
            BiConsumer<Runnable, Runnable> schedule, Runnable retired) {
        invalidate(session);
        if (!requiresDeferral || !dispatching) {
            apply.run();
            return;
        }
        long token = sequence.incrementAndGet();
        pending.put(session, token);
        schedule.accept(() -> {
            if (pending.remove(session, token)) {
                refresh.run();
            }
        }, () -> {
            if (pending.remove(session, token)) {
                retired.run();
            }
        });
    }
}
