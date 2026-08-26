package dev.materione.web.protocol;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.function.Supplier;
import javax.crypto.SecretKey;

/**
 * Post-handshake channel frames (web.md §5.4): the body is encrypted with
 * the session key, then the ciphertext is signed — <b>sign-after-encrypt</b>
 * — and both directions carry monotonic sequence numbers.
 *
 * <pre>{@code
 * { seq:  <monotonic int>,    // per direction; receivers reject gaps/replays/reorders
 *   nonce: <12 random bytes>, // unique per frame (GCM)
 *   ct:    <AES-256-GCM of the body>,
 *   sig:   <Ed25519 over "seq.nonce.ct" in unpadded-base64url ASCII> }
 * }</pre>
 *
 * The signature covers the base64url <em>textual</em> form of the fields —
 * exactly the bytes both runtimes see after JSON encoding — so verification
 * needs no re-canonicalization. A receiver that sees any sequence other than
 * its exact next expectation drops the session ({@code BAD_SEQUENCE}): there
 * is no windowing, no recovery, only a clean reconnect.
 */
public final class FrameCodec {

    private static final Supplier<byte[]> RANDOM_NONCES = () -> {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    };

    /** Sender-side codec: hands out strictly increasing sequence numbers. */
    public static FrameCodec sender() {
        return new FrameCodec(0);
    }

    /** Receiver-side codec: expects sequences starting at 0, strictly in order. */
    public static FrameCodec receiver() {
        return new FrameCodec(0);
    }

    private long nextSequenceToSend;
    private long nextExpectedToReceive;

    private FrameCodec(long start) {
        this.nextSequenceToSend = start;
        this.nextExpectedToReceive = start;
    }

    public JsonObject seal(SecretKey sessionKey, PrivateKey signingKey, byte[] body) {
        return seal(sessionKey, signingKey, body, RANDOM_NONCES);
    }

    /** Deterministic overload for golden fixtures (fixed nonce source). */
    public JsonObject seal(SecretKey sessionKey, PrivateKey signingKey, byte[] body, Supplier<byte[]> nonces) {
        byte[] nonce = nonces.get();
        long seq = nextSequenceToSend++;
        byte[] ciphertext = Envelope.AesGcm.encrypt(sessionKey, nonce, body);
        String nonceText = Wire.encode(nonce);
        String cipherText = Wire.encode(ciphertext);
        byte[] signature = SessionCrypto.sign(
                signingKey, signedData(seq, nonceText, cipherText).getBytes(StandardCharsets.US_ASCII));

        JsonObject frame = new JsonObject();
        frame.addProperty("seq", seq);
        Wire.putBinary(frame, "nonce", nonce);
        Wire.putBinary(frame, "ct", ciphertext);
        Wire.putBinary(frame, "sig", signature);
        return frame;
    }

    /**
     * Opens a frame under {@code sessionKey}, verifying sequence order and
     * the peer's signature before returning the plaintext body.
     */
    public byte[] open(SecretKey sessionKey, PublicKey peerSigningKey, JsonObject frame) {
        JsonNumber seq = readSeq(frame);
        if (seq.value != nextExpectedToReceive) {
            throw new ProtocolException(
                    ProtocolException.Kind.BAD_SEQUENCE,
                    "expected sequence " + nextExpectedToReceive + ", got " + seq.raw);
        }
        String nonceText = textOf(frame, "nonce");
        String cipherText = textOf(frame, "ct");
        byte[] signature = Wire.requiredBinary(frame, "sig");
        if (!SessionCrypto.verify(
                peerSigningKey, signedData(seq.value, nonceText, cipherText).getBytes(StandardCharsets.US_ASCII),
                signature)) {
            throw new ProtocolException(ProtocolException.Kind.BAD_SIGNATURE, "frame signature invalid");
        }
        byte[] plaintext = Envelope.AesGcm.decrypt(sessionKey, Wire.decode(nonceText), Wire.decode(cipherText));
        // Only a fully verified frame (signature AND ciphertext) advances
        // the expectation — any rejection leaves state untouched.
        nextExpectedToReceive++;
        return plaintext;
    }

    // ------------------------------------------------------------------

    private record JsonNumber(long value, String raw) {}

    private static JsonNumber readSeq(JsonObject frame) {
        var element = frame.get("seq");
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "frame lacks a numeric seq");
        }
        String raw = element.getAsJsonPrimitive().getAsString();
        try {
            return new JsonNumber(Long.parseLong(raw), raw);
        } catch (NumberFormatException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "frame seq is not an integer");
        }
    }

    /** Required base64url field returned as its exact textual form (it is what gets verified). */
    private static String textOf(JsonObject frame, String field) {
        return Wire.requiredString(frame, field);
    }

    /** The precise signed payload: {@code seq '.' nonceB64 '.' ctB64} in ASCII. */
    private static String signedData(long seq, String nonceBase64Url, String cipherBase64Url) {
        return seq + "." + nonceBase64Url + "." + cipherBase64Url;
    }
}
