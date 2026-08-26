package dev.materione.web;

import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.web.protocol.SessionBlob;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All live editor sessions of one listener. Session ids are capability
 * material: 128 bits from the CSPRNG, base64url — they appear both in the
 * URL path ({@code /s/<code>}) and inside the encrypted blob, so a URL alone
 * is useless without its fragment key and vice versa (web.md §5.3).
 */
public final class EditorSessions {

    private final KeyPair serverIdentity;
    private final TrustPrompt trustPrompt;
    private final DiagnosticsBus diagnostics;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, EditorSession> sessions = new ConcurrentHashMap<>();

    public EditorSessions(KeyPair serverIdentity, TrustPrompt trustPrompt, DiagnosticsBus diagnostics) {
        this.serverIdentity = serverIdentity;
        this.trustPrompt = trustPrompt;
        this.diagnostics = diagnostics;
    }

    /** Creates a fresh session and announces it (MTR-0031). */
    public EditorSession create() {
        String sessionId = SessionBlob.generateSessionId(random);
        byte[] wrappingKey = new byte[32];
        random.nextBytes(wrappingKey);
        EditorSession session = new EditorSession(
                sessionId, wrappingKey, serverIdentity, trustPrompt, diagnostics);
        sessions.put(sessionId, session);
        diagnostics.publish(dev.materione.api.diagnostics.Diagnostic.info(
                DiagnosticCodes.EDITOR_SESSION_CREATED,
                "editor session " + sessionId + " created; awaiting browser"));
        return session;
    }

    /** The session a hello claims, if one exists in {@code NEW} state. */
    public EditorSession find(String sessionId) {
        return sessions.get(sessionId);
    }

    /** Drops closed sessions (called on disconnect and periodically). */
    public void reapClosed() {
        sessions.values().removeIf(EditorSession::isClosed);
    }

    /** Every currently-active session (for pushes to connected editors). */
    public List<EditorSession> active() {
        return sessions.values().stream().filter(EditorSession::isActive).toList();
    }

    /** Closes every session (listener shutdown / revoke-all). */
    public void closeAll() {
        sessions.values().forEach(EditorSession::close);
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }
}
