package dev.materione.web.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * HKDF-SHA256 (RFC 5869) — the one key-derivation function of the protocol.
 * Deliberately tiny and dependency-free so the browser side can reimplement
 * it from WebCrypto primitives ({@code HMAC} import + two-step derive) with
 * zero ambiguity: extract = HMAC(salt, ikm), expand = chained HMACs over
 * {@code info || counter}.
 */
public final class Hkdf {

    private static final int SHA256_OUTPUT = 32;
    private static final byte[] EMPTY_SALT = new byte[SHA256_OUTPUT];

    private Hkdf() {}

    /** Extract + expand in one call; {@code length} must be ≤ 255 × 32 bytes. */
    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] pseudorandom = hmac(salt == null ? EMPTY_SALT : salt, ikm);
        return expand(pseudorandom, info, length);
    }

    public static byte[] expand(byte[] pseudorandomKey, byte[] info, int length) {
        if (length > 255 * SHA256_OUTPUT) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "HKDF output too long");
        }
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int generated = 0;
        int counter = 1;
        while (generated < length) {
            byte[] blockInput = concat(previous, info, new byte[] {(byte) counter});
            byte[] block = hmac(pseudorandomKey, blockInput);
            int toCopy = Math.min(SHA256_OUTPUT, length - generated);
            System.arraycopy(block, 0, result, generated, toCopy);
            generated += toCopy;
            previous = block;
            counter++;
        }
        return result;
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(normalizeKey(key), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks HmacSHA256", e);
        }
    }

    /** HKDF's extract step expects a key of any length; HMAC keys ≥ hash size need no padding trick. */
    private static byte[] normalizeKey(byte[] key) {
        return key.length == 0 ? EMPTY_SALT : key;
    }

    private static byte[] concat(byte[] first, byte[] second, byte[] third) {
        byte[] out = new byte[first.length + second.length + third.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        System.arraycopy(third, 0, out, first.length + second.length, third.length);
        return out;
    }

    /** The ASCII domain-separation labels both runtimes must use verbatim. */
    public static final class Labels {
        /** info for deriving the AES-256-GCM session key from the ECDH secret. */
        public static final byte[] SESSION_KEY =
                "MATERIONE-EDITOR-v1/session-key".getBytes(StandardCharsets.US_ASCII);
        /** info for deriving the key-confirmation HMAC key (web.md §4.1 step 6). */
        public static final byte[] KEY_CONFIRM =
                "MATERIONE-EDITOR-v1/key-confirm".getBytes(StandardCharsets.US_ASCII);

        private Labels() {}
    }
}
