package com.taarifu.common.security;

import com.taarifu.common.domain.port.ClockPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link InMemoryAuthRateLimiter} anti-automation primitive — S-2 (AUTH-DESIGN §9).
 *
 * <p>Responsibility: proves OTP send-rate, OTP verify-attempt cap, and login lockout/backoff using a
 * controllable {@link ClockPort} (no sleeping, no flakiness, no Docker). These are the behaviours the
 * security review required be built (not assumed) for the auth surface.</p>
 */
class InMemoryAuthRateLimiterTest {

    /** A clock whose "now" the test advances explicitly. */
    private static final class MutableClock implements ClockPort {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-23T10:00:00Z"));

        @Override
        public Instant now() {
            return now.get();
        }

        void advanceSeconds(long seconds) {
            now.updateAndGet(t -> t.plusSeconds(seconds));
        }
    }

    @Test
    void otpSend_isRateLimitedWithin60Seconds() {
        MutableClock clock = new MutableClock();
        InMemoryAuthRateLimiter limiter = new InMemoryAuthRateLimiter(clock);

        assertThat(limiter.allowOtpSend("phone-hash")).isTrue();   // first send allowed
        assertThat(limiter.allowOtpSend("phone-hash")).isFalse();  // immediate resend blocked

        clock.advanceSeconds(61);
        assertThat(limiter.allowOtpSend("phone-hash")).isTrue();   // allowed after the interval
    }

    @Test
    void otpVerifyAttempts_areCappedAtFive() {
        InMemoryAuthRateLimiter limiter = new InMemoryAuthRateLimiter(new MutableClock());
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allowOtpVerifyAttempt("challenge")).isTrue();
        }
        assertThat(limiter.allowOtpVerifyAttempt("challenge")).isFalse(); // 6th blocked
    }

    @Test
    void login_locksOutAfterTenFailures() {
        InMemoryAuthRateLimiter limiter = new InMemoryAuthRateLimiter(new MutableClock());
        String account = "account-hash";

        assertThat(limiter.allowLoginAttempt(account)).isTrue();
        for (int i = 0; i < 10; i++) {
            limiter.recordLoginFailure(account);
        }
        // After 10 failures within the window the account is hard-locked.
        assertThat(limiter.allowLoginAttempt(account)).isFalse();
    }

    @Test
    void login_resetsAfterSuccess() {
        InMemoryAuthRateLimiter limiter = new InMemoryAuthRateLimiter(new MutableClock());
        String account = "account-hash";
        for (int i = 0; i < 10; i++) {
            limiter.recordLoginFailure(account);
        }
        assertThat(limiter.allowLoginAttempt(account)).isFalse();

        limiter.resetLogin(account);
        assertThat(limiter.allowLoginAttempt(account)).isTrue();
    }
}
