package dev.materione.web;

import com.google.gson.JsonObject;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.web.protocol.Envelope;
import dev.materione.web.protocol.FrameCodec;
import dev.materione.web.protocol.HelloMessage;
import dev.materione.web.protocol.HelloReplyMessage;
import dev.materione.web.protocol.ProtocolException;
import dev.materione.web.protocol.SessionBlob;
import dev.materione.web.protocol.SessionCrypto;
import dev.materione.web.protocol.Wire;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;

/**
 * One editor session's state machine (web.md §4.1):
 *
 * <pre>
 * NEW ──hello──► AWAITING_TRUST ──in-game approve──► ACTIVE ──close/error──► CLOSED
 *                     │                                  │
 *                     └── deny / timeout ────────────────┘
 * </pre>
 *
 * The session owns the plugin side of the handshake: it generated the
 * wrapping key, the ephemeral P-256 pair and (via {@link IdentityKeystore})
 * holds the long-term signing key. Frames flow only in {@code ACTIVE}, and
 * every outgoing message is sealed + signed by this session's
 * {@link FrameCodec}; incoming frames verify against the browser's hello key.
 */
public final class EditorSession {

    /** How long an unapproved hello may sit before the prompt expires. */
    public static final long TRUST_TIMEOUT_SECONDS = 120;

    enum State { NEW, AWAITING_TRUST, ACTIVE, CLOSED }

    private final String sessionId;
    private final String ownerId;
    private final byte[] wrappingKey;
    private final KeyPair pluginSigning;
    private KeyPair pluginEphemeral;
    private final TrustPrompt trustPrompt;
    private final EditorWorkstations workstations;
    private final DiagnosticsBus diagnostics;
    private final FrameCodec sendFrames = FrameCodec.sender();
    private final FrameCodec receiveFrames = FrameCodec.receiver();

    private State state = State.NEW;
    private HelloMessage hello;
    private PublicKey editorSigning;
    private SecretKey sessionKey;
    private FrameSink sink;
    private CompletableFuture<TrustPrompt.Decision> pendingTrust;

    EditorSession(
            String sessionId,
            String ownerId,
            byte[] wrappingKey,
            KeyPair pluginSigning,
            TrustPrompt trustPrompt,
            EditorWorkstations workstations,
            DiagnosticsBus diagnostics) {
        this.sessionId = sessionId;
        this.ownerId = ownerId == null ? "" : ownerId;
        this.wrappingKey = wrappingKey.clone();
        this.pluginSigning = pluginSigning;
        this.trustPrompt = trustPrompt;
        this.workstations = workstations;
        this.diagnostics = diagnostics;
        this.pluginEphemeral = dev.materione.web.protocol.KeyFormat.generateP256(new java.security.SecureRandom());
    }

    public String sessionId() {
        return sessionId;
    }

    /** UUID of the player who opened this capability; empty for console sessions. */
    public String ownerId() {
        return ownerId;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    public boolean isClosed() {
        return state == State.CLOSED;
    }

    /** Seals the session blob under the wrapping key for the /s/{code} fetch. */
    public JsonObject sealedBlob() {
        return sealedBlob(sessionId, null, null);
    }

    /**
     * Seals the session blob, optionally carrying relay-mode routing (the
     * mailbox channel + editor capability) and a direct-mode LAN hint — both
     * ride inside the wrapping-key encryption (web.md §3.2/§4.4). The
     * envelope binds to {@code envelopeBinding}: the capability string the
     * browser will have used to fetch the blob — the session id in direct
     * mode, the store code in relay mode (the browser cannot know the
     * session id before decrypting).
     */
    public JsonObject sealedBlob(
            String envelopeBinding,
            dev.materione.web.protocol.SessionBlob.RelayInfo relay,
            dev.materione.web.protocol.SessionBlob.DirectInfo direct) {
        SessionBlob blob = new SessionBlob(
                sessionId,
                java.util.List.of(Wire.VERSION),
                dev.materione.web.protocol.KeyFormat.ed25519RawPublic(pluginSigning.getPublic()),
                relay,
                direct);
        return Envelope.seal(
                new javax.crypto.spec.SecretKeySpec(wrappingKey, "AES"),
                envelopeBinding, SessionBlob.TYPE, blob.bodyBytes());
    }

    /**
     * The URL-fragment half of the capability (web.md §4.1 step 3): the
     * wrapping key travels only in the fragment, which browsers never send
     * to any server. The host composes it with its own base URL.
     */
    public String wrappingKeyFragment() {
        return Wire.encode(wrappingKey);
    }

    /**
     * Entry point of the handshake: validates the hello against this session
     * and asks the human. The returned future completes with the signed
     * plaintext hello-reply once approved, or exceptionally when denied,
     * expired, or mismatched. Never blocks the caller's thread — the trust
     * prompt answers asynchronously.
     */
    public CompletableFuture<String> beginHello(HelloMessage received, FrameSink outbound) {
        if (state != State.NEW) {
            return CompletableFuture.failedFuture(
                    new ProtocolException(ProtocolException.Kind.HANDSHAKE, "session is past hello"));
        }
        if (!received.sessionId().equals(sessionId)) {
            return CompletableFuture.failedFuture(
                    new ProtocolException(ProtocolException.Kind.HANDSHAKE, "hello names another session"));
        }
        if (received.protocolVersion() != Wire.VERSION) {
            return CompletableFuture.failedFuture(new ProtocolException(
                    ProtocolException.Kind.UNSUPPORTED, "protocol version " + received.protocolVersion()
                            + " not supported (server speaks " + Wire.VERSION + ")"));
        }
        hello = Objects.requireNonNull(received, "hello");
        sink = outbound;
        editorSigning = dev.materione.web.protocol.KeyFormat.ed25519Public(hello.editorSigningPubKey());

        state = State.AWAITING_TRUST;
        String noncePrefix = HexFormat.of().formatHex(
                java.util.Arrays.copyOfRange(hello.nonce(), 0, Math.min(4, hello.nonce().length)));
        boolean knownWorkstation =
                workstations != null && workstations.isTrusted(hello.editorSigningPubKey());
        if (knownWorkstation) {
            // Trusted workstation (web.md §5.2): the key was explicitly
            // whitelisted in-game before — reconnect without a new prompt.
            diagnostics.publish(dev.materione.api.diagnostics.Diagnostic.info(
                    DiagnosticCodes.EDITOR_PEER_TRUSTED,
                    "known workstation returns (key " + EditorWorkstations.keyHash(
                            hello.editorSigningPubKey()).substring(0, 8) + ") — skipping prompt"));
            pendingTrust = CompletableFuture.completedFuture(TrustPrompt.Decision.APPROVED);
        } else {
            pendingTrust = trustPrompt.prompt(noncePrefix, hello.browser());
        }
        return pendingTrust
                .orTimeout(TRUST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .thenCompose(decision -> {
                    if (!decision.approved()) {
                        close();
                        return CompletableFuture.failedFuture(new ProtocolException(
                                ProtocolException.Kind.HANDSHAKE, "editor was not approved in game"));
                    }
                    if (decision.trustWorkstation() && workstations != null) {
                        workstations.trust(hello.editorSigningPubKey(), hello.browser());
                    }
                    return CompletableFuture.completedFuture(completeHandshake());
                })
                .whenComplete((reply, error) -> {
                    if (error != null) {
                        close();
                    }
                });
    }

    /** Derives the session key and produces the signed plaintext reply. */
    private String completeHandshake() {
        byte[] sharedSecret = SessionCrypto.ecdhSharedSecret(
                pluginEphemeral.getPrivate(),
                dev.materione.web.protocol.KeyFormat.p256Public(hello.editorEphemeralPubKey()));
        HelloReplyMessage shape = transcriptReplyShape();
        byte[] transcriptHash = SessionCrypto.transcriptHash(
                hello.transcriptPart(), shape.transcriptPart());
        // Confirmation derives from the transcript WITHOUT the confirm field
        // (see HelloReplyMessage#transcriptPart) — no self-reference.
        byte[] confirm = SessionCrypto.keyConfirmation(sharedSecret, transcriptHash);
        this.sessionKey = SessionCrypto.sessionKey(sharedSecret, transcriptHash);

        HelloReplyMessage reply = new HelloReplyMessage(shape.ecdhPub(), confirm);
        JsonObject payload = new JsonObject();
        payload.add("reply", reply.toJson());
        Wire.putBinary(payload, "sig", SessionCrypto.sign(pluginSigning.getPrivate(),
                Wire.canonicalBytes(reply.toJson())));

        this.state = State.ACTIVE;
        return Wire.canonicalJson(payload);
    }

    /**
     * Publishes MTR-0032 once the hello-reply has actually been written.
     * Publishing inside {@link #completeHandshake()} would let the live
     * diagnostics stream race the reply to the browser: the push frame is
     * sealed with the just-derived session key and the client would read it
     * as the handshake message. The transport calls this after send().
     */
    public void announceActive() {
        if (state != State.ACTIVE) {
            return;
        }
        diagnostics.publish(dev.materione.api.diagnostics.Diagnostic.info(
                DiagnosticCodes.EDITOR_PEER_TRUSTED,
                "editor '" + hello.browser() + "' approved; session active"));
    }

    /** The reply as the transcript sees it: ECDH point only, no confirm field. */
    private HelloReplyMessage transcriptReplyShape() {
        return new HelloReplyMessage(
                dev.materione.web.protocol.KeyFormat.p256RawPublic(
                        (java.security.interfaces.ECPublicKey) pluginEphemeral.getPublic()),
                new byte[0]);
    }

    /** Verifies and opens one inbound frame; ACTIVE sessions only. */
    public byte[] openFrame(com.google.gson.JsonObject frame) {
        requireActive();
        return receiveFrames.open(sessionKey, editorSigning, frame);
    }

    /**
     * Seals one outbound frame body and writes it to the connected socket.
     * Synchronized: RPC responses (WS worker threads) and pushes (reload /
     * diagnostics broadcasts from scheduler threads) seal concurrently, and
     * the receiver rejects anything but strict sequence order — the seq must
     * be assigned in exactly the order frames hit the wire.
     */
    public synchronized void sendFrame(byte[] body) {
        requireActive();
        if (sink == null) {
            throw new ProtocolException(ProtocolException.Kind.HANDSHAKE, "no connected socket");
        }
        sink.accept(Wire.canonicalJson(sendFrames.seal(sessionKey, pluginSigning.getPrivate(), body)));
    }

    public void close() {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            if (pendingTrust != null && !pendingTrust.isDone()) {
                pendingTrust.cancel(true);
            }
        }
    }

    private void requireActive() {
        if (state != State.ACTIVE) {
            throw new ProtocolException(ProtocolException.Kind.HANDSHAKE, "session is not active");
        }
    }

    /** Where sealed frame text goes (implemented by the WS connection). */
    public interface FrameSink {
        void accept(String frameText);
    }
}
