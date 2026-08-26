package dev.materione.web.protocol;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The session blob — the trust root handed to the admin as a URL (web.md
 * §4.1). In direct mode it never leaves the machine: the embedded listener
 * serves it at {@code /s/<code>} and the wrapping key rides only in the URL
 * fragment. Contents: what a browser needs to complete the handshake and no
 * more.
 *
 * <p>Sealed with the 256-bit wrapping key through {@link Envelope} (type
 * {@code session-blob}); the code alone is useless, the key alone is useless.
 */
public record SessionBlob(String sessionId, List<Integer> protocolVersions, byte[] pluginSigningPubKey) {

    public static final String TYPE = "session-blob";

    public SessionBlob {
        protocolVersions = protocolVersions == null ? List.of() : List.copyOf(protocolVersions);
    }

    public byte[] bodyBytes() {
        return toJson().toString().getBytes(StandardCharsets.UTF_8);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("sessionId", sessionId);
        var versions = new com.google.gson.JsonArray();
        protocolVersions.forEach(versions::add);
        object.add("protocolVersions", versions);
        Wire.putBinary(object, "pluginSigningPubKey", pluginSigningPubKey);
        return object;
    }

    public static SessionBlob fromBodyBytes(byte[] body) {
        JsonObject object = Wire.parseObject(new String(body, StandardCharsets.UTF_8));
        if (!sessionIdShapeValid(Wire.requiredString(object, "sessionId"))) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "session id malformed");
        }
        var versions = new java.util.ArrayList<Integer>();
        for (var element : object.getAsJsonArray("protocolVersions")) {
            versions.add(element.getAsInt());
        }
        return new SessionBlob(
                Wire.requiredString(object, "sessionId"),
                versions,
                Wire.requiredBinary(object, "pluginSigningPubKey"));
    }

    /**
     * Session ids are capability material (web.md §5.3): ≥100 bits from the
     * CSPRNG, unpadded base64url — 22 characters here.
     */
    public static boolean sessionIdShapeValid(String id) {
        return id.length() >= 22 && id.matches("[A-Za-z0-9_-]+");
    }

    /** Generates a fresh unguessable session id (128-bit space). */
    public static String generateSessionId(java.security.SecureRandom random) {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        return Wire.encode(raw);
    }
}
