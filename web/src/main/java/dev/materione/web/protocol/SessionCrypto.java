package dev.materione.web.protocol;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The handshake math (web.md §4.1 steps 5–7), specified to the byte so the
 * browser can mirror it with WebCrypto:
 *
 * <ol>
 *   <li>Both sides exchange {@code hello} / {@code hello-reply} carrying
 *       P-256 ECDH ephemerals; the <b>transcript</b> is the concatenation of
 *       the two messages' canonical-JSON encodings.</li>
 *   <li>The ECDH shared secret Z feeds two HKDF-SHA256 derivations with the
 *       SHA-256 of the transcript as salt: the AES-256-GCM session key
 *       ({@code info} = "MATERIONE-EDITOR-v1/session-key") and the
 *       key-confirmation HMAC key ({@code info} =
 *       "MATERIONE-EDITOR-v1/key-confirm").</li>
 *   <li>The <b>key-confirmation digest</b> is HMAC-SHA256(confirmKey,
 *       sha256(transcript)); the plugin sends it inside {@code hello-reply}
 *       and the browser recomputes it — a mismatch aborts before any
 *       document flows.</li>
 * </ol>
 *
 * Every frame afterwards is sealed with the session key and signed with the
 * per-side Ed25519 key ({@link FrameCodec}); this class also owns those
 * sign/verify wrappers.
 */
public final class SessionCrypto {

    private SessionCrypto() {}

    /** Raw ECDH over P-256 — both ephemerals, either side's order works. */
    public static byte[] ecdhSharedSecret(PrivateKey ownEphemeral, PublicKey peerEphemeral) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ownEphemeral);
            agreement.doPhase(peerEphemeral, true);
            byte[] secret = agreement.generateSecret();
            // X9.62 uncompressed encoding would carry a 0x04 prefix; the JVM's
            // ECDH generateSecret() already returns the bare X coordinate for
            // P-256 (32 bytes). Enforce that invariant explicitly.
            if (secret.length != 32) {
                throw new ProtocolException(
                        ProtocolException.Kind.HANDSHAKE, "unexpected ECDH secret length " + secret.length);
            }
            return secret;
        } catch (GeneralSecurityException e) {
            throw new ProtocolException(ProtocolException.Kind.HANDSHAKE, "ECDH failed", e);
        }
    }

    public static byte[] transcriptHash(byte[] helloCanonical, byte[] helloReplyCanonical) {
        return sha256(concat(helloCanonical, helloReplyCanonical));
    }

    /** The AES-256-GCM key sealing every frame and payload chunk of the session. */
    public static SecretKey sessionKey(byte[] sharedSecret, byte[] transcriptHash) {
        return new SecretKeySpec(
                Hkdf.derive(sharedSecret, transcriptHash, Hkdf.Labels.SESSION_KEY, 32), "AES");
    }

    /** The key-confirmation digest carried inside hello-reply (see class doc). */
    public static byte[] keyConfirmation(byte[] sharedSecret, byte[] transcriptHash) {
        byte[] confirmKey = Hkdf.derive(sharedSecret, transcriptHash, Hkdf.Labels.KEY_CONFIRM, 32);
        return Hkdf.hmac(confirmKey, transcriptHash);
    }

    public static boolean confirmationsMatch(byte[] expected, byte[] received) {
        return MessageDigest.isEqual(expected, received);
    }

    public static byte[] sign(PrivateKey ed25519Private, byte[] data) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(ed25519Private);
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** False on any mismatch — callers turn this into BAD_SIGNATURE. */
    public static boolean verify(PublicKey ed25519Public, byte[] data, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(ed25519Public);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks SHA-256", e);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
