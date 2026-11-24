package dev.materione.plugin.view;

import dev.materione.plugin.gui.Viewer;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Turns a dirty mark into one render on the owner's thread (views.md section 5.4). It is the {@link ViewWake} the write
 * observer calls on the writer's thread, once per transition from clean to dirty, and it schedules a one-shot through
 * the gateway: at the next tick, or later when the kind's {@code minInterval} since the viewer's last automatic render
 * has not passed. There is no loop over viewers; an idle viewer has no task.
 *
 * <p>When the one-shot runs, on the owner's thread:
 * <ul>
 *   <li>a record that is no longer live or no longer the player's current view of its kind is dropped: a wake never
 *       renders into a window that replaced the one it was made for;</li>
 *   <li>a wake that cannot run, because the player's thread is gone or the player or the kind is missing when it
 *       does, leaves the bit clean, so the next write marks the record dirty and wakes it again;</li>
 *   <li>a bit already clean is a no-op (an explicit refresh got there first);</li>
 *   <li>the bit is cleared before the kind reads state, so a write during the render marks it again and wakes again;</li>
 *   <li>a kind that holds its frame still ({@link ViewKind.RenderOutcome#FROZEN}) leaves the bit set and is tried again
 *       on the next tick.</li>
 * </ul>
 *
 * <p>An explicit refresh ({@link #refreshNow}, {@link #refreshSoon}) is not an automatic render: it ignores the
 * interval and is not counted against it.
 */
public final class ViewScheduler implements ViewWake {

    /** Runs {@code task} on {@code target}'s owner thread after {@code delayTicks}; {@code retired} runs if that thread is gone. */
    @FunctionalInterface
    public interface Later {
        void run(UUID player, long delayTicks, Runnable task, Runnable retired);
    }

    private final ViewRegistry views;
    private final ViewKinds kinds;
    private final LongSupplier tick;
    private final Later later;
    private final Function<UUID, Viewer> viewers;
    private final RenderCounter counter;

    /** Counts automatic renders by definition, for {@code /materione doctor}. */
    @FunctionalInterface
    public interface RenderCounter {
        RenderCounter NONE = (kind, definition) -> { };

        void rendered(String kind, dev.materione.api.NamespacedId definition);
    }

    /**
     * @param tick the server tick now (a virtual clock in tests)
     * @param later the gateway's entity scheduler
     * @param viewers the online viewer for a player id, or null when they are gone
     */
    public ViewScheduler(ViewRegistry views, ViewKinds kinds, LongSupplier tick, Later later,
            Function<UUID, Viewer> viewers, RenderCounter counter) {
        this.views = Objects.requireNonNull(views, "views");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.tick = Objects.requireNonNull(tick, "tick");
        this.later = Objects.requireNonNull(later, "later");
        this.viewers = Objects.requireNonNull(viewers, "viewers");
        this.counter = Objects.requireNonNull(counter, "counter");
    }

    @Override
    public void wake(ViewDirty target) {
        long now = tick.getAsLong();
        int interval = kinds.of(target.kind()).map(ViewKind::minInterval).orElse(1);
        long delay = Math.max(1, target.lastAutomatic() + interval - now);
        later.run(target.player(), delay, () -> automatic(target), target::clearDirty);
    }

    private void automatic(ViewDirty target) {
        if (!target.isDirty() || !current(target)) {
            return;
        }
        Viewer viewer = viewers.apply(target.player());
        ViewKind kind = kinds.of(target.kind()).orElse(null);
        target.clearDirty();
        if (viewer == null || kind == null) {
            return;
        }
        ViewKind.RenderOutcome outcome = ViewKind.RenderOutcome.RENDERED;
        if (target instanceof ViewerRecord record) {
            outcome = kind.renderAutomatic(viewer, record);
        } else if (target instanceof ViewCandidate candidate) {
            kind.evaluate(viewer, candidate);
        }
        switch (outcome) {
            case RENDERED -> {
                target.lastAutomatic(tick.getAsLong());
                if (target instanceof ViewerRecord record) {
                    counter.rendered(record.kind(), record.definition());
                }
            }
            case FROZEN -> {
                if (target.markDirty()) {
                    wake(target);
                }
            }
            case STALE -> { }
        }
    }

    /**
     * Renders {@code record} now, on the calling thread, which must be the record's owner thread: the explicit
     * refresh. The bit is cleared first, so a wake already queued finds it clean and does nothing.
     *
     * @return false when the record is stale or its definition is gone
     */
    public boolean refreshNow(ViewerRecord record) {
        Viewer viewer = viewers.apply(record.player());
        ViewKind kind = kinds.of(record.kind()).orElse(null);
        if (viewer == null || kind == null || !current(record)) {
            return false;
        }
        record.clearDirty();
        return kind.render(viewer, record);
    }

    /**
     * Schedules an explicit refresh from a thread that is not the record's owner: one wake at the next tick that
     * ignores the kind's interval.
     */
    public void refreshSoon(ViewerRecord record) {
        later.run(record.player(), 1, () -> refreshNow(record), () -> { });
    }

    /** Whether the target is still what the player has: a live record that is their current view of its kind, or their candidate. */
    private boolean current(ViewDirty target) {
        if (target instanceof ViewerRecord record) {
            return record.isOpen() && views.current(record.player(), record.kind()).filter(now -> now == record).isPresent();
        }
        return views.candidateOrNull(target.kind(), target.player()) == target;
    }
}
