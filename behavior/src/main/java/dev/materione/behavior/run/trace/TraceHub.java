package dev.materione.behavior.run.trace;

import dev.materione.api.ApiResult;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.trace.LogicTraces;
import dev.materione.api.logic.trace.TraceBatch;
import dev.materione.api.logic.trace.TraceEntry;
import dev.materione.api.logic.trace.TraceFilter;
import dev.materione.api.logic.trace.TraceKind;
import dev.materione.api.logic.trace.TraceLimits;
import dev.materione.api.logic.trace.TraceListener;
import dev.materione.api.logic.trace.TraceSubscription;
import dev.materione.behavior.run.TraceEvent;
import dev.materione.behavior.run.TraceSink;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * The live trace (logic.md §7.6, §32): the {@link TraceSink} the interpreter feeds and the {@link LogicTraces} the
 * API hands out. Engine threads {@link #accept} events, which are filtered per subscription into a bounded
 * {@link TraceQueue}; a host pump calls {@link #flush} on its own schedule to deliver the queues to the
 * listeners. The engine therefore never waits on a subscriber, and a subscriber never sees more than its limits.
 *
 * <p>The hub is free while nobody subscribes: the host installs it as the interpreter's sink only while
 * {@code activity} last reported true, so with no subscription the interpreter builds no events at all.
 * Bukkit-free; it starts no thread (R5).
 */
public final class TraceHub implements LogicTraces, TraceSink, AutoCloseable {

    private final TraceLimits limits;
    private final LongSupplier clock;
    private final Consumer<Boolean> activity;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private boolean closed;

    /**
     * @param limits the caps
     * @param clock epoch milliseconds, injectable for tests
     * @param activity told {@code true} when the first subscription opens and {@code false} when the last one
     *        closes, so the host can install the sink and start its pump only while someone listens
     */
    public TraceHub(TraceLimits limits, LongSupplier clock, Consumer<Boolean> activity) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activity = Objects.requireNonNull(activity, "activity");
    }

    @Override
    public TraceLimits limits() {
        return limits;
    }

    @Override
    public ApiResult<TraceSubscription> subscribe(TraceFilter filter, TraceListener listener) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(listener, "listener");
        Subscription created;
        boolean first;
        synchronized (this) {
            if (closed || subscriptions.size() >= limits.maxSubscriptions()) {
                return ApiResult.fail(DiagnosticCodes.TRACE_REFUSED,
                        "the server already holds " + limits.maxSubscriptions() + " live trace subscriptions");
            }
            created = new Subscription(filter, listener);
            first = subscriptions.isEmpty();
            subscriptions.add(created);
        }
        if (first) {
            activity.accept(true);
        }
        return ApiResult.ok(created);
    }

    @Override
    public int subscriptions() {
        return subscriptions.size();
    }

    /** The interpreter's side: filters one event into every subscription that wants it. Never throws. */
    @Override
    public void accept(TraceEvent event) {
        if (subscriptions.isEmpty()) {
            return;
        }
        TraceEntry entry = null;
        long now = clock.getAsLong();
        for (Subscription subscription : subscriptions) {
            if (!subscription.wants(event)) {
                continue;
            }
            if (entry == null) {
                entry = entryOf(event, now);
            }
            subscription.queue.offer(entry, now);
        }
    }

    /**
     * The pump's side: hands every subscription its pending batch. A listener that throws loses that batch and
     * nothing else; one that closes its own subscription from inside the call is fine.
     */
    public void flush() {
        for (Subscription subscription : subscriptions) {
            TraceBatch batch = subscription.active() ? subscription.queue.take() : null;
            if (batch == null) {
                continue;
            }
            try {
                subscription.listener.accept(batch);
            } catch (RuntimeException ignored) {
                // a broken listener loses its batch; the engine and the other subscriptions are untouched
            }
        }
    }

    /** Whether any subscription still holds entries the pump has not delivered. */
    public boolean hasPending() {
        for (Subscription subscription : subscriptions) {
            if (subscription.queue.pending() > 0) {
                return true;
            }
        }
        return false;
    }

    /** Closes every subscription. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
    }

    private TraceEntry entryOf(TraceEvent event, long now) {
        return new TraceEntry(event.runId(), event.resource(), event.rule(), event.player(),
                event.path().toString(), kindOf(event.type()), cut(event.detail()), now);
    }

    private String cut(String detail) {
        int max = limits.maxDetail();
        return detail.length() <= max ? detail : detail.substring(0, max - 1) + "…";
    }

    static TraceKind kindOf(TraceEvent.Type type) {
        return switch (type) {
            case RUN_STARTED -> TraceKind.RUN_STARTED;
            case STEP_OK -> TraceKind.RAN;
            case STEP_FAILED -> TraceKind.FAILED;
            case STEP_SKIPPED -> TraceKind.SKIPPED;
            case BRANCH -> TraceKind.BRANCH;
            case REQUIRE_FAILED -> TraceKind.REQUIRE_FAILED;
            case CALLED -> TraceKind.CALLED;
            case SUSPENDED -> TraceKind.SUSPENDED;
            case RESUMED -> TraceKind.RESUMED;
            case RUN_ENDED -> TraceKind.RUN_ENDED;
        };
    }

    private void remove(Subscription subscription) {
        boolean last;
        synchronized (this) {
            if (!subscriptions.remove(subscription)) {
                return;
            }
            last = subscriptions.isEmpty();
        }
        if (last) {
            activity.accept(false);
        }
    }

    private final class Subscription implements TraceSubscription {

        private final TraceFilter filter;
        private final TraceListener listener;
        private final TraceQueue queue = new TraceQueue(limits, clock.getAsLong());
        private final Set<String> players;
        private final AtomicBoolean active = new AtomicBoolean(true);

        Subscription(TraceFilter filter, TraceListener listener) {
            this.filter = filter;
            this.listener = listener;
            this.players = filter.players().stream().map(UUID::toString).collect(Collectors.toUnmodifiableSet());
        }

        boolean wants(TraceEvent event) {
            return active.get()
                    && (filter.resources().isEmpty() || filter.resources().contains(event.resource()))
                    && (players.isEmpty() || players.contains(event.player()));
        }

        @Override
        public TraceFilter filter() {
            return filter;
        }

        @Override
        public boolean active() {
            return active.get();
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                remove(this);
            }
        }
    }
}
