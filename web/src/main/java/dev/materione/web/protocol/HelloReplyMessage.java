package dev.materione.web.protocol;

import com.google.gson.JsonObject;

/**
 * The plugin's {@code hello-reply} (web.md §4.1 step 6): the plugin's own
 * P-256 ECDH ephemeral and the key-confirmation digest. After verifying the
 * digest against its own derivation, the browser seals every following frame
 * with the derived session key ({@link SessionCrypto}).
 *
 * @param ecdhPub the plugin's raw 65-byte uncompressed P-256 point
 * @param confirmation HMAC over the transcript hash per the §4.1 spec
 */
public record HelloReplyMessage(byte[] ecdhPub, byte[] confirmation) {

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("type", "hello-reply");
        Wire.putBinary(object, "ecdhPub", ecdhPub);
        Wire.putBinary(object, "confirm", confirmation);
        return object;
    }

    public static HelloReplyMessage fromJson(JsonObject object) {
        if (!"hello-reply".equals(Wire.requiredString(object, "type"))) {
            throw new ProtocolException(ProtocolException.Kind.HANDSHAKE, "not a hello-reply message");
        }
        return new HelloReplyMessage(
                Wire.requiredBinary(object, "ecdhPub"),
                Wire.requiredBinary(object, "confirm"));
    }

    /** Canonical bytes feeding the handshake transcript (second half). */
    public byte[] transcriptPart() {
        // The transcript deliberately excludes `confirm`: the digest is
        // derived FROM the transcript, so including it would be circular.
        // Both sides hash hello plus this reply's ECDH point only.
        JsonObject object = new JsonObject();
        object.addProperty("type", "hello-reply");
        Wire.putBinary(object, "ecdhPub", ecdhPub);
        return Wire.canonicalBytes(object);
    }}
