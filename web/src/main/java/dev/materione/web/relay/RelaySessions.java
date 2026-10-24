package dev.materione.web.relay;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.web.EditorSession;
import dev.materione.web.EditorSessions;
import dev.materione.web.SessionDriver;
import dev.materione.web.protocol.SessionBlob;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relay-mode session factory and registry (web.md §4.1 over the mailbox):
 * creates the channel, the local session, and the sealed blob carrying the
 * editor capability; uploads the blob to the payload store; and hands back
 * the capability URL plus the {@link RelaySession} pump the host parks on an
 * async thread. The URL shape is
 * {@code <editorOrigin>/s/<code>#<wrapping-key>} — identical to direct mode
 * except the origin is the centrally-hosted SPA.
 */
public final class RelaySessions {

    /** What {@link #open} yields: the URL for the admin, the pump for the host. */
    public record Ticket(String url, RelaySession session) {}

    /** Blob TTL: an admin should open the URL within the hour. */
    private static final int BLOB_TTL_SECONDS = 3600;

    private final EditorSessions sessions;
    private final RpcDispatcher dispatcher;
    private final DiagnosticsBus diagnostics;
    private final Map<String, RelaySession> live = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public RelaySessions(EditorSessions sessions, RpcDispatcher dispatcher, DiagnosticsBus diagnostics) {
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.diagnostics = diagnostics;
    }

    /**
     * Opens a relay session end to end: channel → session → sealed blob →
     * store upload. The plugin mints the store code itself so the envelope
     * can bind to it (the browser cannot know the session id before
     * decrypting). {@code directUrl} (nullable) is the LAN hint the SPA may
     * probe and prefer (web.md §4.4).
     */
    public Ticket open(
            RelayClient client,
            String editorOrigin,
            String ownerId,
            String directUrl) throws RelayException {
        RelayClient.Channel channel = client.createChannel();
        EditorSession session = sessions.create(ownerId);
        String code = SessionBlob.generateSessionId(random);
        SessionBlob.RelayInfo relay = new SessionBlob.RelayInfo(
                editorOrigin, channel.channelId(), channel.editorToken());
        SessionBlob.DirectInfo direct =
                directUrl == null || directUrl.isBlank() ? null : new SessionBlob.DirectInfo(directUrl);
        String envelopeJson = Wire.canonicalJson(session.sealedBlob(code, relay, direct));
        client.storePut(envelopeJson, code, BLOB_TTL_SECONDS);
        // Same grammar as direct mode: the SPA boots from / and reads ?s=,
        // then fetches the sealed blob from /s/{code} on this origin — one
        // URL shape and one fetch rule for both hosts.
        String url = trim(editorOrigin) + "/?s=" + code + "#" + session.wrappingKeyFragment();

        RelaySession pump = new RelaySession(client, channel, sessions, dispatcher, diagnostics);
        live.put(session.sessionId(), pump);
        return new Ticket(url, pump);
    }

    /** Pumps still registered (stopped ones are pruned as seen). */
    public List<RelaySession> liveSessions() {
        live.values().removeIf(RelaySession::stopped);
        return new ArrayList<>(live.values());
    }

    /** Flags every pump stopped; loops exit on their next boundary. */
    public void stopAll() {
        live.values().forEach(RelaySession::stop);
        live.clear();
    }

    private static String trim(String origin) {
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }
}
