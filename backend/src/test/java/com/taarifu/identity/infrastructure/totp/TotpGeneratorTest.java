package com.taarifu.identity.infrastructure.totp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the in-house RFC 6238 TOTP generator (VERIFICATION-DESIGN §2.3) — no Spring, no Docker.
 *
 * <p>Proves the load-bearing properties of the staff second factor (N-4): a code is accepted at its own
 * step and within the <b>±1-step window</b> (clock-skew tolerance), and rejected outside it; a wrong code
 * and a null are rejected. Also pins Base32 round-trip correctness since the secret rides through it.</p>
 */
class TotpGeneratorTest {

    private static final int STEP = 30;
    private final TotpGenerator totp = new TotpGenerator(STEP);

    @Test
    void codeIsAcceptedAtItsOwnInstant() {
        String secret = Base32.randomSecret(20);
        long now = 1_700_000_000L;
        String code = totp.codeAt(secret, now);
        assertThat(totp.verify(secret, code, now)).isTrue();
    }

    @Test
    void codeIsAcceptedWithinPlusMinusOneStepWindow() {
        String secret = Base32.randomSecret(20);
        long now = 1_700_000_000L;
        String code = totp.codeAt(secret, now);
        // One step earlier and one step later both still accept (skew tolerance, §2.3).
        assertThat(totp.verify(secret, code, now - STEP)).isTrue();
        assertThat(totp.verify(secret, code, now + STEP)).isTrue();
    }

    @Test
    void codeIsRejectedOutsideTheWindow() {
        String secret = Base32.randomSecret(20);
        long now = 1_700_000_000L;
        String code = totp.codeAt(secret, now);
        // Two steps away is outside ±1 → reject.
        assertThat(totp.verify(secret, code, now + 2 * STEP)).isFalse();
        assertThat(totp.verify(secret, code, now - 2 * STEP)).isFalse();
    }

    @Test
    void wrongAndNullCodesAreRejected() {
        String secret = Base32.randomSecret(20);
        long now = 1_700_000_000L;
        assertThat(totp.verify(secret, "000000", now)).isFalse();
        assertThat(totp.verify(secret, null, now)).isFalse();
        assertThat(totp.verify(null, "123456", now)).isFalse();
    }

    @Test
    void matchedStepReturnsTheStepWithinWindowAndSentinelOutside() {
        // Underpins the V-2 replay defence: the caller needs the matched time-step (not just true/false)
        // to enforce monotonicity. A code matches its own step; a wrong/out-of-window code is NO_MATCH.
        String secret = Base32.randomSecret(20);
        long now = 1_700_000_000L;
        String code = totp.codeAt(secret, now);
        long expectedStep = now / STEP;
        assertThat(totp.matchedStep(secret, code, now)).isEqualTo(expectedStep);
        // A code from the previous step still matches inside the ±1 window, reported as that lower step.
        assertThat(totp.matchedStep(secret, code, now + STEP)).isEqualTo(expectedStep);
        assertThat(totp.matchedStep(secret, "000000", now)).isEqualTo(TotpGenerator.NO_MATCH);
    }

    @Test
    void codeIsSixDigits() {
        String secret = Base32.randomSecret(20);
        assertThat(totp.codeAt(secret, 1_700_000_000L)).hasSize(6).matches("\\d{6}");
    }

    @Test
    void base32RoundTrips() {
        byte[] raw = {0, 1, 2, 3, 4, 5, 6, 7, (byte) 0xFF, (byte) 0x80};
        assertThat(Base32.decode(Base32.encode(raw))).containsExactly(raw);
    }
}
