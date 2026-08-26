package dev.materione.web.protocol;

import com.google.gson.JsonObject;

/**
 * The editor's {@code hello} (web.md §4.1 step 5): a fresh nonce, the
 * session it claims, its Ed25519 signing public key and P-256 ECDH
 * ephemeral public key. The plugin surfaces the nonce in-game for the trust
 * decision — the nonce disambiguates when several editors connect at once.
 *
 * @param browser free-text label of the connecting editor ("Chrome on Windows")
 */
public record HelloMessage(
        byte[] nonce,
        String sessionId,
        int protocolVersion,
        byte[] editorSigningPubKey,
        byte[] editorEphemeralPubKey,
        String browser) {

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("type", "hello");
        Wire.putBinary(object, "nonce", nonce);
        object.addProperty("sessionId", sessionId);
        object.addProperty("protocolVersion", protocolVersion);
        Wire.putBinary(object, "editorSigningPubKey", editorSigningPubKey);
        Wire.putBinary(object, "editorEphemeralPubKey", editorEphemeralPubKey);
        object.addProperty("browser", browser);
        return object;
    }

    public static HelloMessage fromJson(JsonObject object) {
        if (!"hello".equals(Wire.requiredString(object, "type"))) {
            throw new ProtocolException(ProtocolException.Kind.HANDSHAKE, "not a hello message");
        }
        return new HelloMessage(
                Wire.requiredBinary(object, "nonce"),
                Wire.requiredString(object, "sessionId"),
                Wire.intOrNull(object, "protocolVersion"),
                Wire.requiredBinary(object, "editorSigningPubKey"),
                Wire.requiredBinary(object, "editorEphemeralPubKey"),
                Wire.requiredString(object, "browser"));
    }

    /** Canonical bytes feeding the handshake transcript. */
    public byte[] transcriptPart() {
        return Wire.canonicalBytes(toJson());
    }
}
