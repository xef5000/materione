package dev.materione.web.protocol;

import com.google.gson.JsonObject;
import java.security.SecureRandom;
import java.util.function.Supplier;

/**
 * The payload envelope (web.md §5.4) wrapping everything that leaves the
 * channel: the session blob, asset chunks, pack dumps. Structure:
 *
 * <pre>{@code
 * { v: 1,                      // envelope version
 *   sess: <session id>,        // binds the blob to its session
 *   nonce: <12 random bytes>,  // unique per envelope (GCM requirement)
 *   ct: <AES-256-GCM ciphertext> }  // over { type, body }
 * }</pre>
 *
 * The key is caller-chosen: the <em>wrapping key</em> seals the session blob
 * (transported in a URL fragment), the <em>session key</em> seals every other
 * blob and chunk. Receivers learn {@code type} only after decryption.
 */
public final class Envelope {

    /** Current envelope version; readers reject others outright. */
    public static final int VERSION = 1;

    private static final int NONCE_BYTES = 12;
    private static final Supplier<byte[]> RANDOM_NONCES = () -> {
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    };

    private Envelope() {}

    /**
     * Seals {@code (type, plaintext)} under {@code key}. Deterministic when
     * given a deterministic nonce source (golden fixtures inject fixed
     * nonces); production uses {@link SecureRandom}.
     */
    public static JsonObject seal(
            javax.crypto.SecretKey key,
            String sessionId,
            String type,
            byte[] plaintext,
            Supplier<byte[]> nonces) {
        byte[] nonce = nonces.get();
        if (nonce.length != NONCE_BYTES) {
            throw new IllegalStateException("GCM nonce must be 12 bytes");
        }
        JsonObject inner = new JsonObject();
        inner.addProperty("type", type);
        Wire.putBinary(inner, "body", plaintext);
        byte[] ciphertext = AesGcm.encrypt(key, nonce, Wire.canonicalBytes(inner));
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", VERSION);
        envelope.addProperty("sess", sessionId);
        Wire.putBinary(envelope, "nonce", nonce);
        Wire.putBinary(envelope, "ct", ciphertext);
        return envelope;
    }

    /** Convenience overload using secure-random nonces. */
    public static JsonObject seal(javax.crypto.SecretKey key, String sessionId, String type, byte[] plaintext) {
        return seal(key, sessionId, type, plaintext, RANDOM_NONCES);
    }

    /**
     * Opens an envelope under {@code key}, returning {@code [type, body]}.
     * Any version mismatch, foreign session binding, or GCM authentication
     * failure is hostile input.
     */
    public static Opened open(javax.crypto.SecretKey key, String expectedSessionId, JsonObject envelope) {
        if (Wire.intOrNull(envelope, "v") != VERSION) {
            throw new ProtocolException(ProtocolException.Kind.UNSUPPORTED, "unknown envelope version");
        }
        String session = Wire.requiredString(envelope, "sess");
        if (!session.equals(expectedSessionId)) {
            throw new ProtocolException(ProtocolException.Kind.HANDSHAKE, "envelope bound to another session");
        }
        byte[] nonce = Wire.requiredBinary(envelope, "nonce");
        byte[] ciphertext = Wire.requiredBinary(envelope, "ct");
        byte[] plaintext = AesGcm.decrypt(key, nonce, ciphertext);
        JsonObject inner = Wire.parseObject(new String(plaintext, java.nio.charset.StandardCharsets.UTF_8));
        return new Opened(Wire.requiredString(inner, "type"), Wire.requiredBinary(inner, "body"));
    }

    /** Decrypted envelope contents: the payload type and its raw body. */
    public record Opened(String type, byte[] body) {}

    /** AES-256-GCM with 128-bit tags — the exact WebCrypto-compatible parameters. */
    static final class AesGcm {
        private static final int TAG_BITS = 128;

        private AesGcm() {}

        static byte[] encrypt(javax.crypto.SecretKey key, byte[] nonce, byte[] plaintext) {
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(TAG_BITS, nonce));
                return cipher.doFinal(plaintext);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM encrypt failed", e);
            }
        }

        static byte[] decrypt(javax.crypto.SecretKey key, byte[] nonce, byte[] ciphertext) {
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(TAG_BITS, nonce));
                return cipher.doFinal(ciphertext);
            } catch (javax.crypto.AEADBadTagException e) {
                throw new ProtocolException(ProtocolException.Kind.BAD_CIPHERTEXT, "ciphertext failed authentication", e);
            } catch (java.security.GeneralSecurityException e) {
                throw new ProtocolException(ProtocolException.Kind.MALFORMED, "undecryptable ciphertext", e);
            }
        }
    }
}
