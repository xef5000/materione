package dev.materione.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** Canonical JSON and the RPC shapes — the bytes both runtimes must agree on. */
class WireTest {

    @Test
    void objectKeysAreSortedRecursivelyAndStable() {
        JsonObject inner = new JsonObject();
        inner.addProperty("z", 1);
        inner.addProperty("a", 2);
        JsonObject outer = new JsonObject();
        outer.add("b", inner);
        outer.addProperty("a", "x");

        assertThat(Wire.canonicalJson(outer)).isEqualTo("{\"a\":\"x\",\"b\":{\"a\":2,\"z\":1}}");
        // Stability: parse → canonicalize → identical bytes.
        assertThat(Wire.canonicalJson(Wire.parse(Wire.canonicalJson(outer))))
                .isEqualTo(Wire.canonicalJson(outer));
    }

    @Test
    void arraysKeepTheirOrder() {
        JsonObject object = new JsonObject();
        var array = new com.google.gson.JsonArray();
        array.add("c");
        array.add("a");
        object.add("list", array);
        assertThat(Wire.canonicalJson(object)).isEqualTo("{\"list\":[\"c\",\"a\"]}");
    }

    @Test
    void stringsAreEscapedMinimallyButCorrectly() {
        JsonObject object = new JsonObject();
        object.addProperty("s", "quote\" back\\slash\nnewline\ttab");
        assertThat(Wire.canonicalJson(object))
                .isEqualTo("{\"s\":\"quote\\\" back\\\\slash\\nnewline\\ttab\"}");
    }

    @Test
    void requiredFieldsThrowMalformedNotNpe() {
        JsonObject object = new JsonObject();
        object.addProperty("present", "yes");

        assertThatThrownBy(() -> Wire.requiredString(object, "absent"))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.MALFORMED);
        assertThat(Wire.requiredString(object, "present")).isEqualTo("yes");
        assertThat(Wire.intOrNull(object, "absent")).isEqualTo(-1);

        object.addProperty("notb64", "!!!");
        assertThatThrownBy(() -> Wire.requiredBinary(object, "notb64"))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void requestAndResponseShapesRoundTrip() {
        Wire.Request request = new Wire.Request("browse.listPacks", "id-1", new JsonObject());
        Wire.Request parsedRequest = Wire.Request.fromJson(
                Wire.parseObject(Wire.canonicalJson(request.toJson())));
        assertThat(parsedRequest.op()).isEqualTo("browse.listPacks");
        assertThat(parsedRequest.id()).isEqualTo("id-1");

        Wire.Response ok = Wire.Response.success(request, data("packs", 3));
        Wire.Response parsedOk = Wire.Response.fromJson(Wire.parseObject(Wire.canonicalJson(ok.toJson())));
        assertThat(parsedOk.ok()).isTrue();
        assertThat(parsedOk.data().toString()).contains("packs");

        Wire.Response failure = Wire.Response.failure(
                request, new Wire.Error("MTR-0009", "unresolved reference", "sample:sword", "did you mean sample:sword?"));
        Wire.Response parsedFailure =
                Wire.Response.fromJson(Wire.parseObject(Wire.canonicalJson(failure.toJson())));
        assertThat(parsedFailure.ok()).isFalse();
        assertThat(parsedFailure.error().code()).isEqualTo("MTR-0009");
        assertThat(parsedFailure.error().resource()).isEqualTo("sample:sword");
        assertThat(parsedFailure.error().hint()).startsWith("did you mean");

        assertThatThrownBy(() -> Wire.Response.fromJson(Wire.parseObject("{\"op\":\"x\",\"id\":\"y\",\"ok\":false}")))
                .isInstanceOf(ProtocolException.class);
    }

    private static com.google.gson.JsonElement data(String key, int value) {
        JsonObject data = new JsonObject();
        data.addProperty(key, value);
        return data;
    }
}
