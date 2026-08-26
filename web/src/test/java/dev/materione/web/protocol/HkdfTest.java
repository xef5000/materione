package dev.materione.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pins HKDF-SHA256 against RFC 5869 test case 1. */
class HkdfTest {

    @Test
    void matchesRfc5869TestCase1() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");

        byte[] okm = Hkdf.derive(ikm, salt, info, 42);
        assertThat(okm).isEqualTo(hex(
                "3cb25f25faacd57a90434f64d0362f2a"
                        + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865"));
    }

    @Test
    void multiBlockOutputIsChainedDeterministically() {
        byte[] prk = Hkdf.hmac(new byte[32], new byte[] {0x0b});
        byte[] one = Hkdf.expand(prk, new byte[0], 32);
        byte[] two = Hkdf.expand(prk, new byte[0], 64);
        assertThat(two).startsWith(one).hasSize(64);
    }

    private static byte[] hex(String digits) {
        byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
