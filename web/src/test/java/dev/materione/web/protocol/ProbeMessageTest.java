package dev.materione.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ProbeMessageTest {

    private static final String SESSION = "AAECAwQFBgcICQoLDA0ODw";
    private static final String CHALLENGE = Wire.encode(new byte[16]);

    private static String probe(String sessionId, String challenge) {
        JsonObject object = new JsonObject();
        object.addProperty("type", "probe");
        object.addProperty("sessionId", sessionId);
        object.addProperty("challenge", challenge);
        return Wire.canonicalJson(object);
    }

    @Test
    void recognizesProbesAndNothingElse() {
        assertThat(ProbeMessage.looksLikeProbe(probe(SESSION, CHALLENGE))).isTrue();
        // a hello (or any other first message) is never mistaken for a probe
        assertThat(ProbeMessage.looksLikeProbe("{\"type\":\"hello\",\"sessionId\":\"x\"}")).isFalse();
        assertThat(ProbeMessage.looksLikeProbe("not json at all \"probe\"")).isFalse();
        assertThat(ProbeMessage.looksLikeProbe("[\"probe\"]")).isFalse();
        assertThat(ProbeMessage.looksLikeProbe(null)).isFalse();
        // oversized first messages are never parsed as probes
        assertThat(ProbeMessage.looksLikeProbe(probe(SESSION, "A".repeat(600)))).isFalse();
    }

    @Test
    void parsesAWellFormedProbe() {
        ProbeMessage.Request request = ProbeMessage.parse(probe(SESSION, CHALLENGE));
        assertThat(request.sessionId()).isEqualTo(SESSION);
        assertThat(request.challenge()).isEqualTo(CHALLENGE);
    }

    @Test
    void rejectsMalformedSessionIdsAndChallenges() {
        assertThatThrownBy(() -> ProbeMessage.parse(probe("short", CHALLENGE)))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ProbeMessage.parse(probe(SESSION, Wire.encode(new byte[15]))))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ProbeMessage.parse(probe(SESSION, Wire.encode(new byte[65]))))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> ProbeMessage.parse(probe(SESSION, "***not-base64***")))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void signedTextIsDomainSeparatedAndBindsSessionAndChallenge() {
        String text = new String(ProbeMessage.signedBytes(SESSION, CHALLENGE), java.nio.charset.StandardCharsets.UTF_8);
        // starts with a tag no frame ("seq.nonce.ct") or JSON hello-reply can start with
        assertThat(text).startsWith("materione-probe/v1\n");
        assertThat(text).contains(SESSION).contains(CHALLENGE);
        assertThat(ProbeMessage.signedBytes(SESSION, CHALLENGE))
                .isNotEqualTo(ProbeMessage.signedBytes(SESSION, Wire.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})));
        assertThat(ProbeMessage.signedBytes(SESSION, CHALLENGE))
                .isNotEqualTo(ProbeMessage.signedBytes("BBECAwQFBgcICQoLDA0ODw", CHALLENGE));
    }
}
