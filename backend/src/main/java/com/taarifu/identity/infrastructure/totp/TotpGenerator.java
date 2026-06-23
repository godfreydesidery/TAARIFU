package com.taarifu.identity.infrastructure.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

/**
 * A small, self-contained RFC 6238 TOTP implementation (HMAC-SHA1, 6 digits) — VERIFICATION-DESIGN §2.3.
 *
 * <p>Responsibility: compute the time-based one-time code for a Base32 secret at a given instant, and
 * verify a presented code against a <b>±1 step window</b> (clock-skew tolerance, §2.3). A ~40-line
 * in-house HMAC implementation is used deliberately rather than a heavyweight dependency (KISS); the
 * algorithm is fixed (SHA-1, 30-second step, 6 digits) and interoperable with standard authenticator
 * apps. This is pure computation — no Spring, no logging, no secret leaves the method (S-4).</p>
 *
 * <p>WHY SHA-1 (not SHA-256): authenticator apps default to HMAC-SHA1 for TOTP; matching that maximises
 * compatibility. The secret's confidentiality (it is field-encrypted at rest) is what protects the
 * scheme, not the hash choice.</p>
 */
public final class TotpGenerator {

    private static final String HMAC_ALGO = "HmacSHA1";
    private static final int DIGITS = 6;
    private static final int DIGITS_MODULO = 1_000_000; // 10^DIGITS

    private final int stepSeconds;

    /**
     * @param stepSeconds the TOTP time-step (RFC 6238 default 30; config-tunable
     *                    {@code taarifu.security.mfa.totp.step-seconds}).
     */
    public TotpGenerator(int stepSeconds) {
        this.stepSeconds = stepSeconds;
    }

    /**
     * Computes the TOTP code for a secret at an epoch second.
     *
     * @param base32Secret the Base32-encoded shared secret.
     * @param epochSeconds  the Unix time in seconds to compute the code for.
     * @return the zero-padded 6-digit code.
     */
    public String codeAt(String base32Secret, long epochSeconds) {
        long counter = epochSeconds / stepSeconds;
        return hotp(Base32.decode(base32Secret), counter);
    }

    /**
     * Verifies a presented code against a secret at {@code epochSeconds}, accepting the current step and
     * the immediately adjacent steps (±1) to tolerate clock skew and a code entered near a step boundary.
     *
     * @param base32Secret the Base32-encoded shared secret (the active TOTP secret).
     * @param presentedCode the 6-digit code the user entered.
     * @param epochSeconds  the current Unix time in seconds (from the injected clock — testable).
     * @return {@code true} if the code matches within the ±1-step window.
     */
    public boolean verify(String base32Secret, String presentedCode, long epochSeconds) {
        if (presentedCode == null || base32Secret == null) {
            return false;
        }
        String trimmed = presentedCode.trim();
        byte[] key = Base32.decode(base32Secret);
        long counter = epochSeconds / stepSeconds;
        for (long c = counter - 1; c <= counter + 1; c++) {
            if (constantTimeEquals(hotp(key, c), trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** Computes the HOTP code (RFC 4226) for a key and counter — the per-step value TOTP selects. */
    private static String hotp(byte[] key, long counter) {
        try {
            byte[] counterBytes = ByteBuffer.allocate(Long.BYTES).putLong(counter).array();
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            byte[] hash = mac.doFinal(counterBytes);
            // Dynamic truncation (RFC 4226 §5.3).
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % DIGITS_MODULO;
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            // Never include the key/secret in the message (S-4); a TOTP fault must not leak material.
            throw new IllegalStateException("TOTP computation failed", e);
        }
    }

    /** Length-aware constant-time comparison so a wrong code cannot be timed digit-by-digit. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
