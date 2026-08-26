package dev.materione.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import java.security.KeyPair;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * The full handshake, simulated: both sides derive identical session keys
 * and matching confirmation digests from the transcript; any tampering with
 * the transcript breaks the digest; envelopes bind to their session.
 */
class SessionCryptoTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void handshakeDerivesMatchingKeysAndConfirmation() {
        KeyPair editorSigning = KeyFormat.generateEd25519(RANDOM);
        KeyPair pluginEphemeral = KeyFormat.generateP256(RANDOM);
        KeyPair editorEphemeral = KeyFormat.generateP256(RANDOM);

        HelloMessage hello = new HelloMessage(
                nonce(), SessionBlob.generateSessionId(RANDOM), Wire.VERSION,
                KeyFormat.ed25519RawPublic(editorSigning.getPublic()),
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) editorEphemeral.getPublic()),
                "JUnit");
        // The reply carries its confirmation only after the transcript is
        // computable; the placeholder keeps the JSON shape stable so the
        // transcript bytes are final.
        HelloReplyMessage shape = new HelloReplyMessage(
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) pluginEphemeral.getPublic()),
                new byte[0]);

        byte[] transcriptHash = SessionCrypto.transcriptHash(hello.transcriptPart(), shape.transcriptPart());
        byte[] sharedSecret = SessionCrypto.ecdhSharedSecret(
                pluginEphemeral.getPrivate(), KeyFormat.p256Public(hello.editorEphemeralPubKey()));
        byte[] confirmation = SessionCrypto.keyConfirmation(sharedSecret, transcriptHash);

        HelloReplyMessage reply = new HelloReplyMessage(shape.ecdhPub(), confirmation);

        // Browser side: same derivations from its own view of the exchange.
        byte[] browserTranscriptHash =
                SessionCrypto.transcriptHash(hello.transcriptPart(), reply.transcriptPart());
        byte[] browserSecret = SessionCrypto.ecdhSharedSecret(
                editorEphemeral.getPrivate(), KeyFormat.p256Public(reply.ecdhPub()));

        assertThat(browserSecret).isEqualTo(sharedSecret);
        assertThat(SessionCrypto.confirmationsMatch(
                confirmation, SessionCrypto.keyConfirmation(browserSecret, browserTranscriptHash)))
                .isTrue();
        SecretKey pluginKey = SessionCrypto.sessionKey(sharedSecret, transcriptHash);
        SecretKey browserKey = SessionCrypto.sessionKey(browserSecret, browserTranscriptHash);
        assertThat(pluginKey.getEncoded()).isEqualTo(browserKey.getEncoded());
    }

    @Test
    void tamperedTranscriptBreaksTheConfirmationDigest() {
        KeyPair ephemeralA = KeyFormat.generateP256(RANDOM);
        KeyPair ephemeralB = KeyFormat.generateP256(RANDOM);
        byte[] shared = SessionCrypto.ecdhSharedSecret(ephemeralA.getPrivate(), ephemeralB.getPublic());

        HelloMessage honest = hello("honest-nonce-00000001", ephemeralB);
        HelloReplyMessage honestReply = new HelloReplyMessage(
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) ephemeralA.getPublic()), new byte[0]);
        byte[] honestConfirm = SessionCrypto.keyConfirmation(
                shared, SessionCrypto.transcriptHash(honest.transcriptPart(), honestReply.transcriptPart()));

        HelloMessage forged = hello("forged-nonce-0000002", ephemeralB);
        boolean matches = SessionCrypto.confirmationsMatch(honestConfirm, SessionCrypto.keyConfirmation(
                shared, SessionCrypto.transcriptHash(forged.transcriptPart(), honestReply.transcriptPart())));
        assertThat(matches).isFalse();
    }

    @Test
    void envelopeRejectsWrongKeyAndForeignSession() {
        SecretKey key = keyFor("right");
        SecretKey other = keyFor("wrong");
        String sessionId = "session-abc";
        JsonObject sealed = Envelope.seal(key, sessionId, "probe", new byte[] {1, 2, 3}, fixedNonces(9));

        Envelope.Opened opened = Envelope.open(key, sessionId, sealed);
        assertThat(opened.type()).isEqualTo("probe");
        assertThat(opened.body()).containsExactly(1, 2, 3);

        assertThatThrownBy(() -> Envelope.open(other, sessionId, sealed))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.BAD_CIPHERTEXT);
        assertThatThrownBy(() -> Envelope.open(key, "other-session", sealed))
                .isInstanceOf(ProtocolException.class)
                .extracting(e -> ((ProtocolException) e).kind())
                .isEqualTo(ProtocolException.Kind.HANDSHAKE);
    }

    private static HelloMessage hello(String nonceLabel, KeyPair editorEphemeral) {
        KeyPair editorSigning = KeyFormat.generateEd25519(RANDOM);
        return new HelloMessage(
                nonceLabel.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                SessionBlob.generateSessionId(RANDOM),
                Wire.VERSION,
                KeyFormat.ed25519RawPublic(editorSigning.getPublic()),
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) editorEphemeral.getPublic()),
                "JUnit");
    }

    private static SecretKey keyFor(String label) {
        byte[] material = new byte[32];
        byte[] seed = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < material.length; i++) {
            material[i] = seed[i % seed.length];
        }
        return new javax.crypto.spec.SecretKeySpec(material, "AES");
    }

    private static java.util.function.Supplier<byte[]> fixedNonces(int fill) {
        return () -> {
            byte[] nonce = new byte[12];
            java.util.Arrays.fill(nonce, (byte) fill);
            return nonce;
        };
    }

    private static byte[] nonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
