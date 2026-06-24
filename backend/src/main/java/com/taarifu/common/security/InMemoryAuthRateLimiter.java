package com.taarifu.common.security;

import com.taarifu.common.domain.port.ClockPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default, infra-free {@link AuthRateLimiter} — an in-memory sliding window + lockout
 * (AUTH-DESIGN §9, S-2).
 *
 * <p>Responsibility: provides working anti-automation so the auth flows are buildable and unit-testable
 * with <b>no external dependency</b>, and is the <b>single-instance / dev / test default</b>. The
 * production multi-instance target is {@link RedisAuthRateLimiter} (shared across instances,
 * auto-expiring); the two are mutually exclusive on {@code taarifu.ratelimit.backend}: this adapter is the
 * {@code matchIfMissing}/{@code =memory} default, the Redis adapter is selected only by {@code =redis}, so
 * exactly one {@link AuthRateLimiter} bean resolves in every environment and a context with no Redis still
 * boots (W3-2).</p>
 *
 * <p>Defaults (AUTH-DESIGN §9, config-tunable later): OTP send 1 per 60s per recipient; OTP verify 5
 * attempts per challenge; login lockout after 10 failures in a 15-minute window, with exponential
 * backoff after 3. All state is keyed by <b>hashed</b> identifiers supplied by the caller — this class
 * never sees a raw phone/IP (S-4). {@link RedisAuthRateLimiter} preserves these thresholds byte-for-byte.</p>
 *
 * <p>WHY this is acceptable as the default (and what production changes): in-memory counters are lost on
 * restart and not shared across instances, so they are not a hardened control on their own — Redis is
 * required for production multi-instance correctness (set {@code taarifu.ratelimit.backend=redis}). This
 * adapter makes the seam real and testable now.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.ratelimit.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryAuthRateLimiter implements AuthRateLimiter {

    private static final Duration OTP_SEND_INTERVAL = Duration.ofSeconds(60);
    private static final int OTP_VERIFY_MAX = 5;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final int LOGIN_BACKOFF_AFTER = 3;
    private static final int LOGIN_LOCK_AFTER = 10;

    private final ClockPort clock;

    /** Last OTP-send instant per recipient hash. */
    private final Map<String, Instant> lastOtpSend = new ConcurrentHashMap<>();
    /** Verify-attempt count per challenge key. */
    private final Map<String, Integer> otpVerifyAttempts = new ConcurrentHashMap<>();
    /** Login failure state per account hash. */
    private final Map<String, LoginState> loginState = new ConcurrentHashMap<>();

    /**
     * @param clock time source (testable) for the windows/backoff.
     */
    public InMemoryAuthRateLimiter(ClockPort clock) {
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowOtpSend(String recipientHash) {
        Instant now = clock.now();
        Instant last = lastOtpSend.get(recipientHash);
        if (last != null && now.isBefore(last.plus(OTP_SEND_INTERVAL))) {
            return false;
        }
        lastOtpSend.put(recipientHash, now);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowOtpVerifyAttempt(String challengeKey) {
        int attempts = otpVerifyAttempts.merge(challengeKey, 1, Integer::sum);
        return attempts <= OTP_VERIFY_MAX;
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowLoginAttempt(String accountHash) {
        LoginState st = loginState.get(accountHash);
        if (st == null) {
            return true;
        }
        Instant now = clock.now();
        if (st.windowStart.plus(LOGIN_WINDOW).isBefore(now)) {
            // Window elapsed → reset.
            loginState.remove(accountHash);
            return true;
        }
        if (st.failures >= LOGIN_LOCK_AFTER) {
            return false; // hard lock for the remainder of the window
        }
        if (st.failures >= LOGIN_BACKOFF_AFTER && st.nextAllowedAt != null && now.isBefore(st.nextAllowedAt)) {
            return false; // still backing off
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void recordLoginFailure(String accountHash) {
        Instant now = clock.now();
        loginState.compute(accountHash, (k, st) -> {
            if (st == null || st.windowStart.plus(LOGIN_WINDOW).isBefore(now)) {
                st = new LoginState(now);
            }
            st.failures++;
            if (st.failures >= LOGIN_BACKOFF_AFTER) {
                // Exponential backoff with a deterministic component; capped to keep the window meaningful.
                long backoffSeconds = Math.min(1L << (st.failures - LOGIN_BACKOFF_AFTER), 300);
                st.nextAllowedAt = now.plusSeconds(backoffSeconds);
            }
            return st;
        });
    }

    /** {@inheritDoc} */
    @Override
    public void resetLogin(String accountHash) {
        loginState.remove(accountHash);
    }

    /** Per-account login failure window state. */
    private static final class LoginState {
        private final Instant windowStart;
        private int failures;
        private Instant nextAllowedAt;

        private LoginState(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }
}
