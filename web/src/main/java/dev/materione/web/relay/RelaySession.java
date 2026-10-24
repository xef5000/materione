package dev.materione.web.relay;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.web.EditorSession;
import dev.materione.web.EditorSessions;
import dev.materione.web.SessionDriver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One relay-mode editor session's pump (web.md §3.2): a blocking loop that
 * drains the outbound queue to the mailbox (sealed frames and the hello
 * reply, batched, in strict sequence order) and long-polls for inbound
 * messages which it feeds to a {@link SessionDriver} — the exact same
 * hello→trust→frames logic the direct-mode socket uses.
 *
 * <p>Rejoin semantics: a transport hiccup backs off and resumes from the
 * last cursor (retained frames are the catch-up buffer); only a protocol
 * violation, an explicit stop, channel expiry (410), or falling behind the
 * retention horizon ends the session. The host parks {@link #runLoop()} on
 * an async thread (R5: {@code web/} spawns nothing itself).
 */
public final class RelaySession implements SessionDriver.Out {

    /** Worker-side batch cap (fixtures/protocol/relay-mailbox-v1.json). */
    private static final int MAX_BATCH = 32;

    private static final int POLL_WAIT_SECONDS = 15;
    private static final long BACKOFF_MS = 1_000;
    private static final int MAX_BACKOFF_MS = 30_000;

    private final RelayClient client;
    private final RelayClient.Channel channel;
    private final SessionDriver driver;
    private final DiagnosticsBus diagnostics;
    private final LinkedBlockingQueue<String> outbound = new LinkedBlockingQueue<>();
    private volatile boolean stopped;

    RelaySession(
            RelayClient client,
            RelayClient.Channel channel,
            EditorSessions sessions,
            dev.materione.web.rpc.RpcDispatcher dispatcher,
            DiagnosticsBus diagnostics) {
        this.client = client;
        this.channel = channel;
        this.diagnostics = diagnostics;
        this.driver = new SessionDriver(sessions, dispatcher, diagnostics, this);
    }

    /** Blocks until the session ends; the host parks it on an async thread. */
    public void runLoop() {
        long cursor = 0;
        long backoff = BACKOFF_MS;
        while (!stopped) {
            try {
                postOutbound();
                RelayClient.Poll poll = client.poll(
                        channel.channelId(), channel.pluginToken(), cursor, POLL_WAIT_SECONDS);
                if (poll.closed()) {
                    endQuietly("channel closed");
                    return;
                }
                if (cursor > 0 && poll.oldest() != null && poll.oldest() > cursor) {
                    // Frames this reader should have consumed were pruned;
                    // the strict E2E sequence can no longer hold.
                    violation("reader fell behind the relay retention horizon");
                    return;
                }
                for (RelayClient.Delivered delivered : poll.messages()) {
                    driver.handle(delivered.messageText());
                    cursor = delivered.id();
                }
                backoff = BACKOFF_MS;
            } catch (RelayException e) {
                if (stopped) {
                    return;
                }
                if (e.status() == 410 || e.status() == 404) {
                    endQuietly("channel gone (" + e.getMessage() + ")");
                    return;
                }
                if (!e.transientOverload()) {
                    violation("relay error: " + e.getMessage());
                    return;
                }
                if (!sleep(backoff)) {
                    return;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            } catch (Exception e) {
                violation("relay loop failed: " + e);
                return;
            }
        }
        bestEffortChannelClose();
    }

    /** Frames the session sealed (RPC replies, pushes) — queued for posting. */
    @Override
    public void send(String text) {
        outbound.add(text);
    }

    @Override
    public void violation(String detail) {
        diagnostics.publish(Diagnostic.warn(
                DiagnosticCodes.EDITOR_PROTOCOL_VIOLATION,
                detail + " — dropping editor session"));
        stop();
        driver.unbind();
        bestEffortChannelClose();
    }

    /** Stops the loop and closes the channel (revoke, shutdown). */
    public void stop() {
        stopped = true;
    }

    public boolean stopped() {
        return stopped;
    }

    public EditorSession session() {
        return driver.session();
    }

    // ------------------------------------------------------------------

    private void postOutbound() throws RelayException {
        List<String> batch = new ArrayList<>();
        outbound.drainTo(batch, MAX_BATCH);
        while (!batch.isEmpty()) {
            client.postMessages(channel.channelId(), channel.pluginToken(), batch);
            if (batch.size() == MAX_BATCH) {
                batch = new ArrayList<>();
                outbound.drainTo(batch, MAX_BATCH);
            } else {
                batch = new ArrayList<>();
            }
        }
    }

    private void endQuietly(String reason) {
        diagnostics.publish(Diagnostic.info(
                DiagnosticCodes.EDITOR_RELAY_SESSION_ENDED,
                "relay session ended: " + reason));
        stop();
        driver.unbind();
    }

    private void bestEffortChannelClose() {
        try {
            client.closeChannel(channel.channelId(), channel.pluginToken());
        } catch (Exception ignored) {
            // the hourly sweep reaps it; closing is best-effort
        }
    }

    private boolean sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
            return !stopped;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
