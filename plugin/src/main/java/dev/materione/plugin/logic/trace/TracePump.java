package dev.materione.plugin.logic.trace;

import dev.materione.api.scheduling.ScheduledHandle;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.trace.TraceHub;
import org.bukkit.plugin.Plugin;

/**
 * Runs the live trace only while somebody listens (logic.md §7.6, §32): while the hub has a subscription this
 * installs the hub as the interpreter's trace sink and delivers the hub's queues on a short global tick; when the
 * last subscription closes it removes the sink and stops ticking. With no subscription the interpreter builds no
 * trace events at all and nothing is scheduled.
 *
 * <p>An adapter: it owns no policy. The filtering, the limits and the delivery are the hub's.
 */
public final class TracePump {

    /**
     * Ticks between deliveries: 10 ticks is half a second, so a subscriber gets two batches a second at most. The
     * editor relay stores each batch as a frame, so the cadence is also what bounds a streaming editor's cost there.
     */
    static final long INTERVAL_TICKS = 10;

    private final Plugin host;
    private final SchedulingGateway scheduling;
    private final FlowInterpreter interpreter;
    private final TraceHub hub;
    private boolean running;
    private ScheduledHandle next = ScheduledHandle.NONE;

    public TracePump(Plugin host, SchedulingGateway scheduling, FlowInterpreter interpreter, TraceHub hub) {
        this.host = host;
        this.scheduling = scheduling;
        this.interpreter = interpreter;
        this.hub = hub;
    }

    /** The hub's activity callback: the first subscription starts the pump, the last one stops it. */
    public synchronized void active(boolean listening) {
        if (listening == running) {
            return;
        }
        running = listening;
        if (listening) {
            interpreter.traceTo(hub);
            arm();
        } else {
            interpreter.traceTo(null);
            next.cancel();
            next = ScheduledHandle.NONE;
        }
    }

    private void arm() {
        try {
            next = scheduling.executeGlobalLater(host, INTERVAL_TICKS, this::tick);
        } catch (RuntimeException e) {
            // a server that cannot schedule ahead cannot deliver a live trace; the subscription stays quiet
            next = ScheduledHandle.NONE;
        }
    }

    private void tick() {
        try {
            hub.flush();
        } finally {
            synchronized (this) {
                if (running) {
                    arm();
                }
            }
        }
    }
}
