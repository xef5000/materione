package dev.materione.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;

/**
 * Anchors the JVM crypto against external truth before anything else is
 * allowed to depend on it: RFC 8032 Ed25519 test vector 1 pins signatures,
 * round trips pin the DER wrappers, and reconstruction proves fixtures can
 * carry raw key material.
 */
class KeyFormatTest {

    /** RFC 8032 §7.1 TEST 1 (empty message), grouped for verifiability. */
    private static final byte[] SEED_1 = hex(
            "9d61b19deffd5a60" + "ba844af492ec2cc4" + "4449c5697b326919" + "703bac031cae7f60");
    private static final byte[] PUB_1 = hex(
            "d75a980182b10ab7" + "d54bfed3c964073a" + "0ee172f3daa62325" + "af021a68f707511a");
    private static final byte[] SIG_1_EMPTY_MESSAGE = hex(
            "e5564300c360ac72" + "9086e2cc806e828a" + "84877f1eb8e5d974" + "d873e06522490155"
                    + "5fb8821590a33bac" + "c61e39701cf9b46b" + "d25bf5f0595bbe24" + "655141438e7a100b");

    @Test
    void ed25519SignsRfc8032VectorByteForByte() {
        KeyPair pair = KeyFormat.ed25519KeyPair(SEED_1, PUB_1);
        byte[] signature = SessionCrypto.sign(pair.getPrivate(), new byte[0]);
        assertThat(signature).isEqualTo(SIG_1_EMPTY_MESSAGE);
    }

    @Test
    void ed25519VerifiesRfcSignatureAndRejectsTampering() {
        PublicKey publicKey = KeyFormat.ed25519Public(PUB_1);
        assertThat(SessionCrypto.verify(publicKey, new byte[0], SIG_1_EMPTY_MESSAGE)).isTrue();

        byte[] tampered = SIG_1_EMPTY_MESSAGE.clone();
        tampered[0] ^= 0x01;
        assertThat(SessionCrypto.verify(publicKey, new byte[0], tampered)).isFalse();
        assertThat(SessionCrypto.verify(publicKey, new byte[] {1}, SIG_1_EMPTY_MESSAGE)).isFalse();
    }

    @Test
    void generatedEd25519RoundTripsThroughRawMaterial() {
        KeyPair generated = KeyFormat.generateEd25519(new java.security.SecureRandom());
        byte[] seed = KeyFormat.ed25519RawSeed(generated.getPrivate());
        byte[] rawPublic = KeyFormat.ed25519RawPublic(generated.getPublic());

        KeyPair rebuilt = KeyFormat.ed25519KeyPair(seed, rawPublic);
        byte[] message = "round trip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = SessionCrypto.sign(rebuilt.getPrivate(), message);
        assertThat(signature).isEqualTo(SessionCrypto.sign(generated.getPrivate(), message));
        assertThat(SessionCrypto.verify(KeyFormat.ed25519Public(rawPublic), message, signature)).isTrue();
    }

    @Test
    void mismatchedEd25519HalvesAreRejectedAtConstruction() {
        KeyPair other = KeyFormat.generateEd25519(new java.security.SecureRandom());
        byte[] foreignPublic = KeyFormat.ed25519RawPublic(other.getPublic());
        assertThatThrownBy(() -> KeyFormat.ed25519KeyPair(SEED_1, foreignPublic))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void p256ScalarReconstructionMatchesGeneratedKeypairShape() {
        byte[] scalarA = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        KeyPair fromScalar = KeyFormat.p256FromScalar(scalarA);
        assertThat(KeyFormat.p256RawScalar(fromScalar.getPrivate())).isEqualTo(scalarA);
        assertThat(KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) fromScalar.getPublic()))
                .hasSize(65)
                .startsWith((byte) 0x04);
    }

    @Test
    void p256RawPointSurvivesEncodeDecode() {
        KeyPair pair = KeyFormat.generateP256(new java.security.SecureRandom());
        byte[] raw = KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) pair.getPublic());
        PublicKey parsed = KeyFormat.p256Public(raw);
        assertThat(KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) parsed)).isEqualTo(raw);
    }

    @Test
    void p256EcdhIsSymmetricAcrossBothParties() {
        KeyPair a = KeyFormat.generateP256(new java.security.SecureRandom());
        KeyPair b = KeyFormat.generateP256(new java.security.SecureRandom());

        byte[] secretAB = SessionCrypto.ecdhSharedSecret(a.getPrivate(), b.getPublic());
        byte[] secretBA = SessionCrypto.ecdhSharedSecret(b.getPrivate(), a.getPublic());
        assertThat(secretAB).isEqualTo(secretBA).hasSize(32);

        // An unrelated ephemeral derives a different secret.
        KeyPair stranger = KeyFormat.generateP256(new java.security.SecureRandom());
        assertThat(SessionCrypto.ecdhSharedSecret(stranger.getPrivate(), b.getPublic()))
                .isNotEqualTo(secretAB);
    }

    @Test
    void malformedKeyMaterialIsRejectedNotSwallowed() {
        assertThatThrownBy(() -> KeyFormat.ed25519KeyPair(new byte[31], PUB_1))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> KeyFormat.ed25519Public(new byte[31]))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> KeyFormat.p256FromScalar(new byte[16]))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> KeyFormat.p256Public(new byte[64]))
                .isInstanceOf(ProtocolException.class);
    }

    private static byte[] hex(String digits) {
        if (digits.length() % 2 != 0 || !digits.matches("[0-9a-f]+")) {
            throw new AssertionError("bad hex literal (length " + digits.length() + "): " + digits);
        }
        byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
