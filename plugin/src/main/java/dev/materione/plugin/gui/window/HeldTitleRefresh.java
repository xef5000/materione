package dev.materione.plugin.gui.window;

import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/** One owned retry per session; the retry resolves the current GUI rather than retaining a render. */
final class HeldTitleRefresh {
    private final ConcurrentHashMap<GuiSession, Pending> pending = new ConcurrentHashMap<>();

    void ensure(GuiSession session, Runnable retry, BiFunction<Runnable, Runnable, ScheduledHandle> schedule) {
        Pending next = new Pending();
        if (pending.putIfAbsent(session, next) != null) {
            return;
        }
        try {
            next.handle = schedule.apply(() -> {
                if (pending.remove(session, next)) {
                    retry.run();
                }
            }, () -> pending.remove(session, next));
            if (pending.get(session) != next) {
                next.handle.cancel();
            }
        } catch (RuntimeException error) {
            pending.remove(session, next);
            throw error;
        }
    }

    void cancel(GuiSession session) {
        Pending old = pending.remove(session);
        if (old != null && old.handle != null) {
            old.handle.cancel();
        }
    }

    private static final class Pending {
        volatile ScheduledHandle handle;
    }
}
