package dev.materione.web.protocol;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Raw-key conversion for the two schemes the protocol pins (web.md §5.2):
 * <b>Ed25519</b> signatures (32-byte seeds and public keys) and
 * <b>P-256 ECDH</b> ephemerals (32-byte scalars, 65-byte uncompressed points).
 * The wire carries raw bytes because that is what WebCrypto imports/exports;
 * the JVM speaks PKCS#8/X.509, so this class owns the two fixed DER wrappers.
 *
 * <p>Both encodings are constant by specification, which also makes golden
 * fixtures deterministic ({@code fixtures/protocol/editor-protocol-v1.json}):
 * a fixture carries raw seed/scalar bytes plus the matching public half, and
 * every runtime must reconstruct identical keys from them. Ed25519 cannot
 * derive its public half from the seed through the JCA alone, so a keypair
 * is always reconstructed from <em>both</em> halves and sanity-checked by a
 * sign/verify round trip — a mismatched pair is rejected at construction,
 * never mid-session.
 */
public final class KeyFormat {

    private static final String ED25519 = "Ed25519";
    private static final String EC = "EC";

    /** PKCS#8 prefix wrapping an Ed25519 seed: SEQUENCE { INTEGER 0, [0] { OID 1.3.101.112, OCTET STRING(32) } }. */
    private static final byte[] ED25519_PKCS8_PREFIX = {
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20};
    /** X.509 prefix wrapping an Ed25519 raw public key. */
    private static final byte[] ED25519_X509_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    // P-256 (secp256r1): canonical PKCS#8 (RFC 5958 + 5915) and SPKI. The
    // PKCS#8 carries the full AlgorithmIdentifier and the RFC 5915
    // ECPrivateKey wrapper — OpenSSL rejects the shortened form even though
    // the JVM tolerates it, and both runtimes must consume identical bytes.
    private static final byte[] P256_PKCS8_PREFIX = {
            0x30, 0x41, 0x02, 0x01, 0x00, 0x30, 0x13, 0x06, 0x07, 0x2a, (byte) 0x86, 0x48,
            (byte) 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce,
            0x3d, 0x03, 0x01, 0x07, 0x04, 0x25, 0x30, 0x25, 0x02, 0x01, 0x01, 0x04, 0x20};
    private static final byte[] P256_X509_PREFIX = {
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01,
            0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00};

    private KeyFormat() {}

    // ------------------------------------------------------------------
    // Ed25519

    /** Generates a fresh Ed25519 signing keypair (key size fixed via the algorithm parameter spec). */
    public static KeyPair generateEd25519(SecureRandom random) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ED25519);
            generator.initialize(java.security.spec.NamedParameterSpec.ED25519, random);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks Ed25519 (required: Java 15+)", e);
        }
    }

    /**
     * Reconstructs an Ed25519 keypair from both raw halves and proves them
     * consistent with one sign/verify round trip over fixed bytes.
     */
    public static KeyPair ed25519KeyPair(byte[] seed, byte[] rawPublic) {
        if (seed.length != 32 || rawPublic.length != 32) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED,
                    "Ed25519 key material must be 32-byte pairs (got " + seed.length + "/" + rawPublic.length + ")");
        }
        PrivateKey privateKey;
        PublicKey publicKey;
        try {
            KeyFactory factory = KeyFactory.getInstance(ED25519);
            privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(concat(ED25519_PKCS8_PREFIX, seed)));
            publicKey = factory.generatePublic(new X509EncodedKeySpec(concat(ED25519_X509_PREFIX, rawPublic)));
        } catch (GeneralSecurityException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "bad Ed25519 key material", e);
        }
        byte[] proof = new byte[] {'m', 't', 'r', 'n'};
        try {
            Signature signer = Signature.getInstance(ED25519);
            signer.initSign(privateKey);
            signer.update(proof);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(ED25519);
            verifier.initVerify(publicKey);
            verifier.update(proof);
            if (!verifier.verify(signature)) {
                throw new ProtocolException(
                        ProtocolException.Kind.MALFORMED, "Ed25519 seed and public key do not match");
            }
        } catch (GeneralSecurityException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "Ed25519 consistency check failed", e);
        }
        return new KeyPair(publicKey, privateKey);
    }

    /** Reconstructs an Ed25519 public key from its raw 32 bytes. */
    public static PublicKey ed25519Public(byte[] raw) {
        if (raw.length != 32) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "Ed25519 public key must be 32 bytes");
        }
        try {
            return KeyFactory.getInstance(ED25519)
                    .generatePublic(new X509EncodedKeySpec(concat(ED25519_X509_PREFIX, raw)));
        } catch (GeneralSecurityException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "bad Ed25519 public key", e);
        }
    }

    /** Extracts the raw 32-byte public key. */
    public static byte[] ed25519RawPublic(PublicKey publicKey) {
        return tail(publicKey.getEncoded(), 32);
    }

    /** Extracts the raw 32-byte seed (private key material — handle like a secret). */
    public static byte[] ed25519RawSeed(PrivateKey privateKey) {
        return tail(privateKey.getEncoded(), 32);
    }

    // ------------------------------------------------------------------
    // P-256 ECDH

    /** Generates a fresh P-256 ECDH ephemeral keypair. */
    public static KeyPair generateP256(SecureRandom random) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(EC);
            generator.initialize(new ECGenParameterSpec("secp256r1"), random);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks P-256 ECDH", e);
        }
    }

    /**
     * Reconstructs a P-256 keypair from its raw scalar; the public point is
     * recomputed from the curve base point, so fixtures need only the scalar.
     */
    public static KeyPair p256FromScalar(byte[] scalar) {
        if (scalar.length != 32) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "P-256 scalar must be 32 bytes");
        }
        try {
            KeyFactory factory = KeyFactory.getInstance(EC);
            ECParameterSpec curve = curveParameters();
            ECPrivateKey privateKey =
                    (ECPrivateKey) factory.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), curve));
            ECPublicKey publicKey =
                    (ECPublicKey) factory.generatePublic(new ECPublicKeySpec(multiplyBase(privateKey.getS()), curve));
            return new KeyPair(publicKey, privateKey);
        } catch (InvalidKeySpecException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "bad P-256 scalar", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks P-256 ECDH", e);
        }
    }

    /** Parses WebCrypto's raw form {@code 0x04 || X || Y} into a public key. */
    public static PublicKey p256Public(byte[] raw) {
        if (raw.length != 65 || raw[0] != 0x04) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "expected a 65-byte uncompressed P-256 point");
        }
        try {
            return KeyFactory.getInstance(EC)
                    .generatePublic(new ECPublicKeySpec(decodePoint(raw), curveParameters()));
        } catch (InvalidKeySpecException e) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "bad P-256 point", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks P-256 ECDH", e);
        }
    }

    /** Encodes a P-256 public point as WebCrypto's raw form. */
    public static byte[] p256RawPublic(ECPublicKey publicKey) {
        return encodePoint(publicKey.getW());
    }

    /** Extracts the raw 32-byte scalar (private key material — handle like a secret). */
    public static byte[] p256RawScalar(PrivateKey privateKey) {
        return leftPad32(((ECPrivateKey) privateKey).getS());
    }

    public static byte[] encodePoint(ECPoint w) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(leftPad32(w.getAffineX()), 0, out, 1, 32);
        System.arraycopy(leftPad32(w.getAffineY()), 0, out, 33, 32);
        return out;
    }

    public static ECPoint decodePoint(byte[] raw) {
        return new ECPoint(
                new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
    }

    // ------------------------------------------------------------------
    // internals

    private static ECParameterSpec curveParameters() {
        try {
            var parameters = java.security.AlgorithmParameters.getInstance(EC);
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM lacks secp256r1 algorithm parameters", e);
        }
    }

    /**
     * Scalar multiplication of the secp256r1 base point via plain modular
     * arithmetic over the published domain parameters. Used only to rebuild
     * deterministic fixture keys (public half from a fixed scalar); live
     * handshakes run through {@code KeyAgreement}, never this. The result is
     * verified to lie on the curve, so a corrupted constant fails loudly.
     */
    private static ECPoint multiplyBase(BigInteger s) {
        BigInteger p = new BigInteger(
                "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
        BigInteger a = new BigInteger(
                "ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16);
        BigInteger b = new BigInteger(
                "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
        BigInteger gx = new BigInteger(
                "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
        BigInteger gy = new BigInteger(
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);

        BigInteger[] acc = {BigInteger.ZERO, BigInteger.ONE}; // infinity sentinel (not on the curve)
        BigInteger[] add = {gx, gy};
        for (int i = 0; i < s.bitLength(); i++) {
            if (s.testBit(i)) {
                acc = pointAdd(acc, add, p, a);
            }
            add = pointAdd(add, add, p, a);
        }
        BigInteger x = acc[0].mod(p);
        BigInteger y = acc[1].mod(p);
        BigInteger onCurve = y.multiply(y)
                .subtract(x.multiply(x).multiply(x).add(a.multiply(x)).add(b))
                .mod(p);
        if (!onCurve.equals(BigInteger.ZERO)) {
            throw new IllegalStateException("derived fixture point is not on secp256r1");
        }
        return new ECPoint(x, y);
    }

    private static BigInteger[] pointAdd(BigInteger[] p1, BigInteger[] p2, BigInteger modulus, BigInteger a) {
        if (isInfinity(p1)) {
            return p2.clone();
        }
        if (isInfinity(p2)) {
            return p1.clone();
        }
        BigInteger lambda;
        if (p1[0].equals(p2[0])) {
            if (p1[1].add(p2[1]).mod(modulus).signum() == 0) {
                return new BigInteger[] {BigInteger.ZERO, BigInteger.ONE}; // P + (-P)
            }
            // Doubling: λ = (3x² + a) / 2y
            lambda = BigInteger.valueOf(3).multiply(p1[0]).multiply(p1[0]).add(a)
                    .multiply(BigInteger.TWO.multiply(p1[1]).modInverse(modulus))
                    .mod(modulus);
        } else {
            // λ = (y2 − y1) / (x2 − x1)
            lambda = p2[1].subtract(p1[1])
                    .multiply(p2[0].subtract(p1[0]).modInverse(modulus))
                    .mod(modulus);
        }
        BigInteger x = lambda.multiply(lambda).subtract(p1[0]).subtract(p2[0]).mod(modulus);
        BigInteger y = lambda.multiply(p1[0].subtract(x)).subtract(p1[1]).mod(modulus);
        return new BigInteger[] {x, y};
    }

    private static boolean isInfinity(BigInteger[] point) {
        return point[0].signum() == 0 && point[1].equals(BigInteger.ONE);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static byte[] tail(byte[] encoded, int length) {
        if (encoded.length < length) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "encoded key too short");
        }
        return Arrays.copyOfRange(encoded, encoded.length - length, encoded.length);
    }

    /** Big-endian two's-complement → unsigned, zero-padded/truncated to exactly 32 bytes. */
    private static byte[] leftPad32(BigInteger value) {
        byte[] array = value.toByteArray();
        if (array.length > 32 && array[0] == 0) {
            array = Arrays.copyOfRange(array, 1, array.length);
        }
        if (array.length > 32) {
            throw new ProtocolException(ProtocolException.Kind.MALFORMED, "coordinate exceeds 32 bytes");
        }
        byte[] out = new byte[32];
        System.arraycopy(array, 0, out, 32 - array.length, array.length);
        return out;
    }
}
