package dev.materione.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * Channel frames: round trips interoperate across directions, and every
 * hostile mutation — gap, replay, reorder, tamper — is rejected as the
 * specific failure class web.md §5.4 promises.
 */
class FrameCodecTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void sealedFramesRoundTripAcrossBothDirections() {
        SecretKey sessionKey = key();
        KeyPair pluginSigning = KeyFormat.generateEd25519(RANDOM);
        KeyPair editorSigning = KeyFormat.generateEd25519(RANDOM);

        FrameCodec pluginToEditor = FrameCodec.sender();
        FrameCodec editorReceiving = FrameCodec.receiver();
        byte[] body = Wire.canonicalBytes(request("browse.listPacks", "r1"));
        JsonObject frame = pluginToEditor.seal(sessionKey, pluginSigning.getPrivate(), body);

        assertThat(editorReceiving.open(sessionKey, pluginSigning.getPublic(), frame)).isEqualTo(body);
        // Second frame in strict order also opens.
        JsonObject second = pluginToEditor.seal(sessionKey, pluginSigning.getPrivate(), body);
        assertThat(editorReceiving.open(sessionKey, pluginSigning.getPublic(), second)).isEqualTo(body);

        FrameCodec editorToPlugin = FrameCodec.sender();
        FrameCodec pluginReceiving = FrameCodec.receiver();
        JsonObject reply = editorToPlugin.seal(sessionKey, editorSigning.getPrivate(), body);
        assertThat(pluginReceiving.open(sessionKey, editorSigning.getPublic(), reply)).isEqualTo(body);
    }

    @Test
    void sequenceGapsReplaysAndReordersAreRejected() {
        SecretKey sessionKey = key();
        KeyPair signing = KeyFormat.generateEd25519(RANDOM);
        PublicKey peer = signing.getPublic();

        FrameCodec sender = FrameCodec.sender();
        FrameCodec receiver = FrameCodec.receiver();
        byte[] body = new byte[] {1};

        JsonObject zero = sender.seal(sessionKey, signing.getPrivate(), body);
        JsonObject one = sender.seal(sessionKey, signing.getPrivate(), body);
        JsonObject two = sender.seal(sessionKey, signing.getPrivate(), body);

        assertThat(receiver.open(sessionKey, peer, zero)).isEqualTo(body);

        // Gap: skipping to seq 2 while expecting 1.
        assertThatThrownBy(() -> receiver.open(sessionKey, peer, two))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.BAD_SEQUENCE);

        assertThat(receiver.open(sessionKey, peer, one)).isEqualTo(body);

        // Replay: seq 1 again after it was consumed.
        assertThatThrownBy(() -> receiver.open(sessionKey, peer, one))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.BAD_SEQUENCE);

        // A rejected frame never advances the expectation: seq 2 still
        // opens right after the rejected replay of seq 1.
        assertThat(receiver.open(sessionKey, peer, two)).isEqualTo(body);
    }

    @Test
    void tamperedCiphertextFailsAuthenticationNotDecryption() {
        SecretKey sessionKey = key();
        SecretKey otherKey = key();
        KeyPair signing = KeyFormat.generateEd25519(RANDOM);

        FrameCodec sender = FrameCodec.sender();
        FrameCodec receiver = FrameCodec.receiver();
        JsonObject frame = sender.seal(sessionKey, signing.getPrivate(), new byte[] {7});

        // A different session key cannot open it (GCM tag check).
        assertThatThrownBy(() -> receiver.open(otherKey, signing.getPublic(), frame))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.BAD_CIPHERTEXT);
        // The failed attempt must not advance sequence state.
        assertThat(receiver.open(sessionKey, signing.getPublic(), frame)).containsExactly(7);
    }

    @Test
    void forgedSignatureIsRejected() {
        SecretKey sessionKey = key();
        KeyPair honest = KeyFormat.generateEd25519(RANDOM);
        KeyPair attacker = KeyFormat.generateEd25519(RANDOM);

        FrameCodec receiver = FrameCodec.receiver();
        // A frame signed by an attacker's key at the currently-expected
        // sequence must fail on signature (not on sequence).
        JsonObject forged =
                FrameCodec.sender().seal(sessionKey, attacker.getPrivate(), new byte[] {7});
        assertThatThrownBy(() -> receiver.open(sessionKey, honest.getPublic(), forged))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.BAD_SIGNATURE);

        // Rejected frames must not advance sequence state: the honest
        // sender's first frame (seq 0) still opens afterwards.
        FrameCodec honestSender = FrameCodec.sender();
        assertThat(receiver.open(sessionKey, honest.getPublic(),
                honestSender.seal(sessionKey, honest.getPrivate(), new byte[] {7})))
                .containsExactly(7);
        assertThat(receiver.open(sessionKey, honest.getPublic(),
                honestSender.seal(sessionKey, honest.getPrivate(), new byte[] {8})))
                .containsExactly(8);
    }

    private static SecretKey key() {
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        return new javax.crypto.spec.SecretKeySpec(material, "AES");
    }

    private static JsonObject request(String op, String id) {
        JsonObject request = new JsonObject();
        request.addProperty("op", op);
        request.addProperty("id", id);
        request.add("payload", new JsonObject());
        return request;
    }
}
