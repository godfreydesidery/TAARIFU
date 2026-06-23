package com.taarifu.identity.infrastructure.totp;

import java.security.SecureRandom;

/**
 * Minimal RFC 4648 Base32 codec for TOTP shared secrets (VERIFICATION-DESIGN §2.3).
 *
 * <p>Responsibility: encode/decode the Base32 alphabet that authenticator apps (Google Authenticator,
 * Authy, …) expect in an {@code otpauth://} URI, and generate a fresh random secret. A tiny in-house
 * codec is used deliberately rather than adding a dependency just for Base32 (KISS, CLAUDE.md §3) — the
 * alphabet and padding are fixed and well-specified.</p>
 *
 * <p>WHY no padding on encode and tolerant of {@code =}/whitespace/case on decode: authenticator apps
 * present the secret without padding and users may paste it with spaces; decoding must accept that while
 * encoding produces the compact unpadded form the {@code otpauth} URI uses.</p>
 */
public final class Base32 {

    /** The RFC 4648 Base32 alphabet (upper-case A–Z, 2–7). */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] DECODE = new int[128];
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        java.util.Arrays.fill(DECODE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODE[ALPHABET.charAt(i)] = i;
        }
    }

    private Base32() {
    }

    /**
     * Generates a fresh random Base32 secret.
     *
     * @param bytes the entropy size in bytes (20 = 160-bit, the RFC 6238 / authenticator default).
     * @return the unpadded Base32-encoded secret.
     */
    public static String randomSecret(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return encode(buf);
    }

    /**
     * Encodes bytes to unpadded Base32.
     *
     * @param data the raw bytes.
     * @return the Base32 string (no {@code =} padding).
     */
    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(ALPHABET.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    /**
     * Decodes a Base32 string (case-insensitive; ignores {@code =} padding and whitespace).
     *
     * @param encoded the Base32 string.
     * @return the decoded bytes.
     * @throws IllegalArgumentException if a non-alphabet character is present.
     */
    public static byte[] decode(String encoded) {
        String clean = encoded.replace("=", "").replaceAll("\\s", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(clean.length() * 5 / 8);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int val = c < 128 ? DECODE[c] : -1;
            if (val < 0) {
                throw new IllegalArgumentException("Invalid Base32 character");
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
