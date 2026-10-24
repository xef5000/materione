package dev.materione.web.protocol;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The session blob — the trust root handed to the admin as a URL (web.md
 * §4.1). In direct mode it never leaves the machine: the embedded listener
 * serves it at {@code /s/<code>}` and the wrapping key rides only in the URL
 * fragment. In relay mode (M5) the same sealed blob is uploaded to the
 * payload store and additionally carries the {@link RelayInfo} a browser
 * needs to join the mailbox channel, plus an optional {@link DirectInfo}
 * hint for LAN auto-preference (§4.4).
 *
 * <p>Sealed with the 256-bit wrapping key through {@link Envelope} (type
 * {@code session-blob}); the code alone is useless, the key alone is useless.
 */
public record SessionBlob(
        String sessionId,
        List<Integer> protocolVersions,
        byte[] pluginSigningPubKey,
        RelayInfo relay,
        DirectInfo direct) {

    public static final String TYPE = "session-blob";

    /** How a browser reaches the relay mailbox and proves itself there. */
    public record RelayInfo(String base, String channelId, String editorToken) {}

    /** Best-effort direct-mode hint (LAN auto-preference); never authoritative. */
    public record DirectInfo(String url) {}

    public SessionBlob {
        protocolVersions = protocolVersions == null ? List.of() : List.copyOf(protocolVersions);
    }

    /** Direct-mode shape (no relay/direct blocks). */
    public SessionBlob(String sessionId, List<Integer> protocolVersions, byte[] pluginSigningPubKey) {
        this(sessionId, protocolVersions, pluginSigningPubKey, null, null);
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
        if (relay != null) {
            JsonObject block = new JsonObject();
            block.addProperty("base", relay.base());
            block.addProperty("channelId", relay.channelId());
            block.addProperty("editorToken", relay.editorToken());
            object.add("relay", block);
        }
        if (direct != null) {
            JsonObject block = new JsonObject();
            block.addProperty("url", direct.url());
            object.add("direct", block);
        }
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
        RelayInfo relay = null;
        if (object.has("relay") && object.get("relay").isJsonObject()) {
            JsonObject block = object.getAsJsonObject("relay");
            relay = new RelayInfo(
                    Wire.requiredString(block, "base"),
                    Wire.requiredString(block, "channelId"),
                    Wire.requiredString(block, "editorToken"));
        }
        DirectInfo direct = null;
        if (object.has("direct") && object.get("direct").isJsonObject()) {
            direct = new DirectInfo(Wire.requiredString(object.getAsJsonObject("direct"), "url"));
        }
        return new SessionBlob(
                Wire.requiredString(object, "sessionId"),
                versions,
                Wire.requiredBinary(object, "pluginSigningPubKey"),
                relay,
                direct);
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
