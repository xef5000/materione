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
 *
 * <p>Besides the routing it carries the <em>self-description</em> the panel
 * needs before any RPC: the protocol versions this build accepts at hello
 * (negotiated against the panel's own list) and the {@link #capabilities()}
 * naming the editor features this build implements — so a panel newer than
 * the connected jar hides unsupported UI instead of collecting per-call
 * "unknown op" errors (web.md §4.2).
 */
public record SessionBlob(
        String sessionId,
        List<Integer> protocolVersions,
        List<String> capabilities,
        byte[] pluginSigningPubKey,
        RelayInfo relay,
        DirectInfo direct) {

    public static final String TYPE = "session-blob";

    /**
     * The capability vocabulary this build implements — user-visible editor
     * feature areas the panel may gate UI on, not RPC op names (ops come and
     * go without changing what the editor does). Content-type names follow
     * the content module's registrations; {@code models}, {@code assets} and
     * {@code delivery} cover the non-type authoring surface, and {@code gui} covers the GUI
     * builder's typed ops ({@code gui.get}, {@code gui.edit}, {@code gui.customizeItem},
     * {@code gui.render}, {@code gui.validate}) and the preview-in-game ops
     * ({@code runtime.openGui}, {@code runtime.previewTarget}), all gated in editor-op-gates.json.
     * Keep sorted: the list rides into the golden fixture byte-for-byte.
     */
    public static final List<String> CURRENT_CAPABILITIES = List.of(
            "assets",
            "blocks",
            "delivery",
            "furniture",
            "glyphs",
            "gui",
            "items",
            "models",
            "paintings",
            "recipes",
            "sounds",
            "text-effects");

    /** How a browser reaches the relay mailbox and proves itself there. */
    public record RelayInfo(String base, String channelId, String editorToken) {}

    /** Best-effort direct-mode hint (LAN auto-preference); never authoritative. */
    public record DirectInfo(String url) {}

    public SessionBlob {
        protocolVersions = protocolVersions == null ? List.of() : List.copyOf(protocolVersions);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
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
        var caps = new com.google.gson.JsonArray();
        capabilities.forEach(caps::add);
        object.add("capabilities", caps);
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
        if (object.has("protocolVersions") && object.get("protocolVersions").isJsonArray()) {
            for (var element : object.getAsJsonArray("protocolVersions")) {
                versions.add(element.getAsInt());
            }
        }
        var capabilities = new java.util.ArrayList<String>();
        if (object.has("capabilities") && object.get("capabilities").isJsonArray()) {
            for (var element : object.getAsJsonArray("capabilities")) {
                capabilities.add(element.getAsString());
            }
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
                capabilities,
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
