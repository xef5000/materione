package dev.materione.web.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The wire encoding (web.md §4.2/§5.4): canonical JSON over Gson, unpadded
 * base64url for all binary material, and the two RPC shapes —
 * {@code {op, id, payload}} requests and {@code {op, id, ok, data|error}}
 * responses. Requests flow <em>both</em> directions: the browser issues
 * document-store calls, the plugin pushes live events with the same shape.
 *
 * <p>Canonical form is load-bearing, not cosmetic: handshake transcripts are
 * hashed and signed as canonical-JSON bytes, so both runtimes (JVM here,
 * WebCrypto in the browser) must agree byte-for-byte. The rules are simple
 * enough to reimplement anywhere: UTF-8, no whitespace, object keys sorted
 * lexically (recursively), strings escaped the way Gson escapes them, no
 * floats anywhere on the wire (ints, strings, booleans, arrays, objects
 * only), binary values carried as unpadded base64url.
 */
public final class Wire {

    /** Protocol version this build speaks; negotiated at hello (web.md §4.2). */
    public static final int VERSION = 1;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Wire() {}

    // ------------------------------------------------------------------
    // Canonical JSON

    /** Serializes to canonical bytes: compact, keys sorted recursively. */
    public static byte[] canonicalBytes(JsonElement element) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sorted(element), sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical JSON as a string (transcripts, fixtures). */
    public static String canonicalJson(JsonElement element) {
        return new String(canonicalBytes(element), StandardCharsets.UTF_8);
    }

    /** Parses strict JSON; anything the parser rejects is MALFORMED. */
    public static JsonElement parse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "invalid JSON", e);
        }
    }

    public static JsonObject parseObject(String json) {
        JsonElement element = parse(json);
        if (!element.isJsonObject()) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "expected a JSON object");
        }
        return element.getAsJsonObject();
    }

    /** Required string property; absent or non-string is MALFORMED. */
    public static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "missing string field '" + field + "'");
        }
        return value.getAsString();
    }

    /** Required base64url-decoded binary property. */
    public static byte[] requiredBinary(JsonObject object, String field) {
        try {
            return B64D.decode(requiredString(object, field));
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "field '" + field + "' is not base64url", e);
        }
    }

    /** Optional int property (protocol uses ints only — no floats). */
    public static int intOrNull(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return -1;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "field '" + field + "' is not a number");
        }
        return value.getAsInt();
    }

    public static void putString(JsonObject object, String field, String value) {
        object.addProperty(field, value);
    }

    public static void putBinary(JsonObject object, String field, byte[] value) {
        object.addProperty(field, encode(value));
    }

    public static String encode(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    public static byte[] decode(String base64Url) {
        return B64D.decode(base64Url);
    }

    // ------------------------------------------------------------------
    // RPC shapes

    /** One RPC call, either direction ({@code push.*} ops need no reply). */
    public record Request(String op, String id, JsonObject payload) {
        public JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("op", op);
            object.addProperty("id", id);
            object.add("payload", payload);
            return object;
        }

        public static Request fromJson(JsonObject object) {
            String op = Wire.requiredString(object, "op");
            String id = Wire.requiredString(object, "id");
            JsonElement payload = object.get("payload");
            if (payload == null || !payload.isJsonObject()) {
                throw new ProtocolException(ProtocolException.Kind.MALFORMED, "request '" + op + "' lacks a payload object");
            }
            return new Request(op, id, payload.getAsJsonObject());
        }
    }

    /** A structured error mirroring the plugin's diagnostic shape (plugin.md §5). */
    public record Error(String code, String message, String resource, String hint) {
        @Override
        public String toString() {
            return code + ": " + message;
        }
    }

    /** One RPC reply: exactly one of {@code data}/{@code error} per {@code ok}. */
    public record Response(String op, String id, boolean ok, JsonElement data, Error error) {
        public static Response success(Request forRequest, JsonElement data) {
            return new Response(forRequest.op(), forRequest.id(), true, data, null);
        }

        public static Response failure(Request forRequest, Error error) {
            return new Response(forRequest.op(), forRequest.id(), false, null, error);
        }

        public JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("op", op);
            object.addProperty("id", id);
            object.addProperty("ok", ok);
            if (ok) {
                object.add("data", data);
            } else {
                JsonObject err = new JsonObject();
                err.addProperty("code", error.code());
                err.addProperty("message", error.message());
                if (error.resource() != null) {
                    err.addProperty("resource", error.resource());
                }
                if (error.hint() != null) {
                    err.addProperty("hint", error.hint());
                }
                object.add("error", err);
            }
            return object;
        }

        public static Response fromJson(JsonObject object) {
            String op = Wire.requiredString(object, "op");
            String id = Wire.requiredString(object, "id");
            var okElement = object.get("ok");
            if (okElement == null || !okElement.isJsonPrimitive()) {
                throw new ProtocolException(ProtocolException.Kind.MALFORMED, "response lacks ok flag");
            }
            boolean ok = okElement.getAsBoolean();
            if (ok) {
                JsonElement data = object.get("data");
                if (data == null || data.isJsonNull()) {
                    data = new JsonObject();
                }
                return new Response(op, id, true, data, null);
            }
            JsonObject err = object.getAsJsonObject("error");
            if (err == null) {
                throw new ProtocolException(ProtocolException.Kind.MALFORMED, "failed response lacks error");
            }
            JsonElement resource = err.get("resource");
            JsonElement hint = err.get("hint");
            return new Response(
                    op, id, false, null,
                    new Error(
                            Wire.requiredString(err, "code"),
                            Wire.requiredString(err, "message"),
                            resource != null && resource.isJsonPrimitive() ? resource.getAsString() : null,
                            hint != null && hint.isJsonPrimitive() ? hint.getAsString() : null));
        }
    }

    // ------------------------------------------------------------------
    // internals

    /**
     * Returns a copy sorted by key at every object level (arrays keep
     * order). Input is never mutated.
     */
    private static JsonElement sorted(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return element;
        }
        if (element.isJsonArray()) {
            var array = element.getAsJsonArray();
            var out = new com.google.gson.JsonArray();
            for (JsonElement child : array) {
                out.add(sorted(child));
            }
            return out;
        }
        JsonObject object = element.getAsJsonObject();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            keys.add(entry.getKey());
        }
        java.util.Collections.sort(keys);
        JsonObject out = new JsonObject();
        for (String key : keys) {
            out.add(key, sorted(object.get(key)));
        }
        return out;
    }

    /** Compact writer over an already-sorted tree. */
    private static void writeCanonical(JsonElement element, StringBuilder sb) {
        if (element == null || element.isJsonNull()) {
            sb.append("null");
        } else if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            // The wire carries no floats (class doc): numbers print as their
            // integer/long lexical form, booleans bare, everything else is
            // an escaped string.
            if (primitive.isBoolean() || primitive.isNumber()) {
                sb.append(primitive.toString());
            } else {
                quoteInto(sb, primitive.getAsString());
            }
        } else if (element.isJsonArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonElement child : element.getAsJsonArray()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeCanonical(child, sb);
            }
            sb.append(']');
        } else {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quoteInto(sb, entry.getKey());
                sb.append(':');
                writeCanonical(entry.getValue(), sb);
            }
            sb.append('}');
        }
    }

    /** Gson-compatible minimal JSON string escaping. */
    private static void quoteInto(StringBuilder sb, String raw) {
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
