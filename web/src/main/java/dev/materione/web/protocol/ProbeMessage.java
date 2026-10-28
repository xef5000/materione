package dev.materione.web.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;

/**
 * The direct-mode reachability proof (web.md §4.4 "LAN auto-preference"): a
 * pre-hello challenge the cloud-hosted SPA sends over a candidate direct
 * WebSocket before it commits to that transport.
 *
 * <pre>
 * browser → plugin  {"type":"probe","sessionId":S,"challenge":C}   C = base64url, 16..64 bytes
 * plugin  → browser {"type":"probe-reply","sessionId":S,"sig":Σ}   Σ = Ed25519(identity, signedBytes(S,C))
 * </pre>
 *
 * <p>The browser verifies Σ against the {@code pluginSigningPubKey} it
 * already holds from the (end-to-end encrypted) session blob. Only the server
 * that holds this session's identity key <em>and</em> still has the session
 * unclaimed can answer, so a bare TCP/WebSocket accept, a different Materione
 * instance, or any other service sitting on the same {@code IP:port} cannot
 * pass. The challenge is fresh per probe, so a recorded reply is useless.
 *
 * <p>The signed text starts with a domain-separation tag that no other signed
 * artifact of the protocol can begin with (hello-reply signs a JSON object,
 * frames sign {@code seq.nonce.ct}), so the signature cannot be replayed as —
 * or forged from — anything else. Probing is a direct-socket affair only: the
 * relay is untrusted and must never get a signing oracle, even a harmless one.
 */
public final class ProbeMessage {

    public static final String TYPE = "probe";
    public static final String REPLY_TYPE = "probe-reply";
    /** Domain-separation tag; versioned so the proof can evolve without ambiguity. */
    public static final String DOMAIN = "materione-probe/v1";

    private static final int MIN_CHALLENGE_BYTES = 16;
    private static final int MAX_CHALLENGE_BYTES = 64;

    /** A parsed, validated probe request. */
    public record Request(String sessionId, String challenge) {}

    private ProbeMessage() {}

    /** True when {@code text} is a JSON object whose {@code type} is {@code probe}. */
    public static boolean looksLikeProbe(String text) {
        if (text == null || text.length() > 512 || !text.contains("\"" + TYPE + "\"")) {
            return false;
        }
        try {
            JsonObject object = Wire.parseObject(text);
            JsonElement type = object.get("type");
            return type != null && type.isJsonPrimitive() && TYPE.equals(type.getAsString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Parses and validates a probe; throws {@link ProtocolException} on malformed input. */
    public static Request parse(String text) {
        JsonObject object = Wire.parseObject(text);
        String sessionId = Wire.requiredString(object, "sessionId");
        String challenge = Wire.requiredString(object, "challenge");
        if (!SessionBlob.sessionIdShapeValid(sessionId)) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "probe session id malformed");
        }
        int length;
        try {
            length = Wire.decode(challenge).length;
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "probe challenge malformed");
        }
        if (length < MIN_CHALLENGE_BYTES || length > MAX_CHALLENGE_BYTES) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "probe challenge has a bad length");
        }
        return new Request(sessionId, challenge);
    }

    /** The exact bytes both sides sign/verify. Mirrored by webui/src/lib/protocol/probe.ts. */
    public static byte[] signedBytes(String sessionId, String challenge) {
        return (DOMAIN + "\n" + sessionId + "\n" + challenge).getBytes(StandardCharsets.UTF_8);
    }

    /** The wire reply for an already-computed signature. */
    public static JsonObject reply(String sessionId, byte[] signature) {
        JsonObject object = new JsonObject();
        object.addProperty("type", REPLY_TYPE);
        object.addProperty("sessionId", sessionId);
        Wire.putBinary(object, "sig", signature);
        return object;
    }
}
