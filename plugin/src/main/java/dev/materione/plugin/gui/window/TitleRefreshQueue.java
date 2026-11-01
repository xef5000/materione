package dev.materione.plugin.gui.window;

import dev.materione.plugin.gui.session.GuiSession;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** Cancels superseded deferred window refreshes without retaining completed sessions. */
final class TitleRefreshQueue {
    private final ConcurrentHashMap<GuiSession, Long> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    void invalidate(GuiSession session) {
        pending.remove(session);
    }

    void update(GuiSession session, boolean requiresDeferral, boolean dispatching, Runnable apply, Runnable refresh,
            BiConsumer<Runnable, Runnable> schedule) {
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
        }, () -> pending.remove(session, token));
    }
}
