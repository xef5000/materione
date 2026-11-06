package dev.materione.web.rpc.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.api.ApiResult;
import dev.materione.api.logic.trace.LogicTraces;
import dev.materione.api.logic.trace.TraceBatch;
import dev.materione.api.logic.trace.TraceEntry;
import dev.materione.api.logic.trace.TraceFilter;
import dev.materione.api.logic.trace.TraceLimits;
import dev.materione.api.logic.trace.TraceSubscription;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The live trace ops (logic.md §7.6, §32): {@code logic.trace.subscribe} opens a stream of
 * {@code push.logic.trace} frames to the calling editor session for one GUI, and
 * {@code logic.trace.unsubscribe} closes it. Both are pure translators over {@link LogicTraces} (R9) and are gated
 * on the {@code logic} session capability.
 *
 * <p>A subscription is opt-in per session and per resource, scoped to the session's own player unless the
 * request says {@code allPlayers}, and held on a lease: the editor renews it by subscribing again, and a lease
 * that lapses, or a session that ended, closes it. Frames are sized for the relay (entries are already capped and
 * their text cut by the hub; a frame is further trimmed to {@value #MAX_FRAME_BYTES} bytes), and the server
 * delivers at most twice a second, so the stream cannot flood a mailbox.
 */
public final class LogicTraceOps {

    /** How long a subscription lives without being renewed. */
    public static final int LEASE_SECONDS = 300;
    /** The most resources one editor session traces at once. */
    public static final int MAX_RESOURCES_PER_SESSION = 4;
    /** A frame's body is kept under this size; longer batches lose their tail, counted as dropped. */
    public static final int MAX_FRAME_BYTES = 48 * 1024;
    /** The longest rule id and step path sent; longer text is cut. */
    static final int MAX_TEXT = 160;
    private static final int MAX_RESOURCE = 128;

    /** The slice of an editor session the stream needs: is it alive, and write one frame to it. */
    public interface Channel {
        boolean open();

        void send(byte[] body);
    }

    private final LogicTraces traces;
    private final Function<String, Channel> channelOf;
    private final Supplier<Optional<UUID>> player;
    private final LongSupplier clock;
    private final Map<String, Map<String, Lease>> bySession = new HashMap<>();
    private long sequence;

    private record Lease(TraceSubscription subscription, boolean allPlayers, long expiresAt) {}

    private LogicTraceOps(LogicTraces traces, Function<String, Channel> channelOf, Supplier<Optional<UUID>> player,
            LongSupplier clock) {
        this.traces = traces;
        this.channelOf = channelOf;
        this.player = player;
        this.clock = clock;
    }

    /**
     * Registers the two ops.
     *
     * @param traces the logic API's trace surface
     * @param channelOf the editor session by id, or null when it is gone
     * @param player the Minecraft player who opened the calling session, if there is one
     * @param clock epoch milliseconds
     */
    public static void register(RpcDispatcher dispatcher, LogicTraces traces, Function<String, Channel> channelOf,
            Supplier<Optional<UUID>> player, LongSupplier clock) {
        var ops = new LogicTraceOps(traces, channelOf, player, clock);
        dispatcher.register("logic.trace.subscribe", ops::subscribe);
        dispatcher.register("logic.trace.unsubscribe", ops::unsubscribe);
    }

    // ------------------------------------------------------------------ ops

    private Wire.Response subscribe(Wire.Request request) {
        JsonObject body = request.payload();
        try {
            String resource = EditorContentOps.requiredString(body, "resource");
            if (resource.length() > MAX_RESOURCE || resource.indexOf(':') < 1 || resource.endsWith(":")) {
                return EditorContentOps.badRequest(request, "'resource' must be a resource id such as pack:name");
            }
            boolean allPlayers = body.has("allPlayers") && body.get("allPlayers").getAsBoolean();
            String sessionId = RpcDispatcher.currentSessionId();
            Channel channel = channelOf.apply(sessionId);
            if (channel == null || !channel.open()) {
                return EditorContentOps.badRequest(request, "no active editor session to stream to");
            }
            Optional<UUID> owner = allPlayers ? Optional.empty() : player.get();
            TraceFilter filter = owner.map(uuid -> TraceFilter.resource(resource, uuid))
                    .orElseGet(() -> TraceFilter.resource(resource));
            long now = clock.getAsLong();
            long expiresAt = now + LEASE_SECONDS * 1000L;
            synchronized (this) {
                sweep(now);
                Map<String, Lease> mine = bySession.computeIfAbsent(sessionId, key -> new HashMap<>());
                Lease existing = mine.get(resource);
                if (existing != null && existing.subscription().active() && existing.allPlayers() == allPlayers) {
                    mine.put(resource, new Lease(existing.subscription(), allPlayers, expiresAt));
                    return success(request, resource, owner.isPresent());
                }
                if (existing == null && mine.size() >= MAX_RESOURCES_PER_SESSION) {
                    return refused(request, "this editor session already traces " + MAX_RESOURCES_PER_SESSION
                            + " GUIs; turn one off first", resource);
                }
                ApiResult<TraceSubscription> opened = traces.subscribe(filter,
                        batch -> deliver(sessionId, resource, channel, batch));
                if (!opened.ok()) {
                    return EditorContentOps.error(request, opened.diagnostics(), resource,
                            "close a live trace in another editor, then try again");
                }
                if (existing != null) {
                    existing.subscription().close();
                }
                mine.put(resource, new Lease(opened.value(), allPlayers, expiresAt));
                return success(request, resource, owner.isPresent());
            }
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
            return EditorContentOps.badRequest(request, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            return EditorContentOps.failure(request, e);
        }
    }

    private Wire.Response unsubscribe(Wire.Request request) {
        try {
            String resource = EditorContentOps.optionalString(request.payload(), "resource");
            String sessionId = RpcDispatcher.currentSessionId();
            int closed = 0;
            synchronized (this) {
                Map<String, Lease> mine = bySession.get(sessionId);
                if (mine != null) {
                    for (String key : resource == null ? java.util.List.copyOf(mine.keySet()) : java.util.List.of(resource)) {
                        Lease lease = mine.remove(key);
                        if (lease != null) {
                            lease.subscription().close();
                            closed++;
                        }
                    }
                    if (mine.isEmpty()) {
                        bySession.remove(sessionId);
                    }
                }
            }
            JsonObject data = new JsonObject();
            data.addProperty("unsubscribed", closed);
            return Wire.Response.success(request, data);
        } catch (Exception e) {
            return EditorContentOps.failure(request, e);
        }
    }

    private Wire.Response success(Wire.Request request, String resource, boolean ownPlayerOnly) {
        TraceLimits limits = traces.limits();
        JsonObject data = new JsonObject();
        data.addProperty("subscribed", true);
        data.addProperty("resource", resource);
        data.addProperty("scope", ownPlayerOnly ? "player" : "all");
        data.addProperty("leaseSeconds", LEASE_SECONDS);
        JsonObject caps = new JsonObject();
        caps.addProperty("eventsPerSecond", limits.eventsPerSecond());
        caps.addProperty("maxEntriesPerRun", limits.maxEntriesPerRun());
        caps.addProperty("maxBatch", limits.maxBatch());
        data.add("limits", caps);
        return Wire.Response.success(request, data);
    }

    private Wire.Response refused(Wire.Request request, String message, String resource) {
        return Wire.Response.failure(request, new Wire.Error(
                dev.materione.api.diagnostics.DiagnosticCodes.TRACE_REFUSED.code(), message, resource, null));
    }

    // ------------------------------------------------------------------ stream

    /** The hub's listener: one frame per batch to the session that asked, or the subscription closes. */
    private void deliver(String sessionId, String resource, Channel channel, TraceBatch batch) {
        try {
            if (!channel.open()) {
                closeSession(sessionId);
                return;
            }
            long now = clock.getAsLong();
            long seq;
            synchronized (this) {
                Lease lease = bySession.getOrDefault(sessionId, Map.of()).get(resource);
                if (lease == null || lease.expiresAt() <= now) {
                    closeSession(sessionId, resource);
                    return;
                }
                seq = ++sequence;
            }
            channel.send(Wire.canonicalBytes(frame(resource, batch, seq)));
        } catch (RuntimeException e) {
            closeSession(sessionId);
        }
    }

    /** The {@code push.logic.trace} frame of a batch, trimmed to {@link #MAX_FRAME_BYTES}. */
    static JsonObject frame(String resource, TraceBatch batch, long sequence) {
        int keep = batch.entries().size();
        while (true) {
            JsonObject frame = build(resource, batch, sequence, keep);
            if (keep == 0 || Wire.canonicalBytes(frame).length <= MAX_FRAME_BYTES) {
                return frame;
            }
            keep = keep / 2;
        }
    }

    private static JsonObject build(String resource, TraceBatch batch, long sequence, int keep) {
        JsonArray entries = new JsonArray();
        for (TraceEntry entry : batch.entries().subList(0, keep)) {
            JsonObject json = new JsonObject();
            json.addProperty("run", entry.runId());
            json.addProperty("rule", cut(entry.rule()));
            json.addProperty("path", cut(entry.path()));
            json.addProperty("kind", entry.kind().wireName());
            if (!entry.detail().isEmpty()) {
                json.addProperty("detail", cut(entry.detail()));
            }
            json.addProperty("at", entry.atMillis());
            entries.add(json);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("resource", resource);
        payload.addProperty("seq", sequence);
        payload.add("entries", entries);
        int dropped = batch.dropped() + (batch.entries().size() - keep);
        if (dropped > 0) {
            payload.addProperty("dropped", dropped);
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("op", "push.logic.trace");
        frame.addProperty("id", "trace-" + sequence);
        frame.add("payload", payload);
        return frame;
    }

    private static String cut(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }

    // ------------------------------------------------------------------ leases

    /** Closes every lease that lapsed or whose session ended. Caller holds the lock. */
    private void sweep(long now) {
        var sessions = bySession.entrySet().iterator();
        while (sessions.hasNext()) {
            var entry = sessions.next();
            Channel channel = channelOf.apply(entry.getKey());
            boolean alive = channel != null && channel.open();
            var leases = entry.getValue().entrySet().iterator();
            while (leases.hasNext()) {
                Lease lease = leases.next().getValue();
                if (!alive || lease.expiresAt() <= now || !lease.subscription().active()) {
                    lease.subscription().close();
                    leases.remove();
                }
            }
            if (entry.getValue().isEmpty()) {
                sessions.remove();
            }
        }
    }

    private synchronized void closeSession(String sessionId) {
        Map<String, Lease> mine = bySession.remove(sessionId);
        if (mine != null) {
            mine.values().forEach(lease -> lease.subscription().close());
        }
    }

    private synchronized void closeSession(String sessionId, String resource) {
        Map<String, Lease> mine = bySession.get(sessionId);
        if (mine != null) {
            Lease lease = mine.remove(resource);
            if (lease != null) {
                lease.subscription().close();
            }
            if (mine.isEmpty()) {
                bySession.remove(sessionId);
            }
        }
    }
}
