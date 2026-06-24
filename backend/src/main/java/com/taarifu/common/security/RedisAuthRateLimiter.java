package com.taarifu.common.security;

import com.taarifu.common.domain.port.ClockPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The production, multi-instance {@link AuthRateLimiter} — a Redis-backed sliding window + lockout, sharing
 * state across every app instance (W3-2, AUTH-DESIGN §9, wave3-review W3-2 / TR-3 / G14, S-2).
 *
 * <p>Responsibility: the same anti-automation contract as {@link InMemoryAuthRateLimiter}, but with the
 * counters in <b>Redis</b> so a horizontally-scaled deployment enforces ONE shared budget per identity —
 * the in-memory adapter's budget is per-process, so behind a load balancer an attacker simply spreads
 * attempts across instances to multiply the effective cap. Selected by
 * {@code taarifu.ratelimit.backend=redis}; when this bean is present (registered under the name
 * {@code redisAuthRateLimiter}) {@link InMemoryAuthRateLimiter} yields to it via its
 * {@code @ConditionalOnMissingBean(name = "redisAuthRateLimiter")}. With the property unset/=memory this bean
 * is absent and the in-memory adapter is the single-instance default — so dev, test, and a single-node
 * deployment are unchanged and still boot with no Redis present.</p>
 *
 * <p><b>Thresholds are byte-for-byte identical to the in-memory adapter</b> (the contract callers depend on):
 * OTP send 1 per 60s per recipient; OTP verify 5 attempts per challenge; login hard-lock after 10 failures
 * in a 15-minute window, with exponential backoff (capped at 300s) after 3. The semantics are preserved with
 * native, atomic Redis primitives:
 * <ul>
 *   <li><b>OTP send</b> — {@code SET key 1 NX EX 60}: the {@code NX} makes the first send in the window win
 *       atomically and the {@code EX} expires it after the interval, so a concurrent resend across instances
 *       cannot both succeed (exactly the {@code lastOtpSend < now-60s} gate, race-safe);</li>
 *   <li><b>OTP verify</b> — {@code INCR}, with {@code EXPIRE} set on the first hit: the Nth attempt is admitted
 *       iff the post-increment count {@code <= 5}, matching {@code attempts <= OTP_VERIFY_MAX};</li>
 *   <li><b>login state</b> — a small Redis hash ({@code failures}, {@code windowStart}, {@code nextAllowedAt})
 *       with a {@link #LOGIN_WINDOW} TTL that mirrors the in-memory {@code LoginState}; the window self-expires
 *       (the same "window elapsed → reset" behaviour) and {@link #resetLogin(String)} deletes the key on a
 *       successful login. Recording a failure is a read-modify-write under a per-key {@code WATCH}-free
 *       compute on the single hash, advancing failures + recomputing the backoff exactly as in memory.</li>
 * </ul></p>
 *
 * <p><b>Keys carry no PII (S-4, PDPA 2022/2023):</b> the caller already passes a <b>hashed</b> identifier
 * (account/recipient/challenge hash — see {@link AuthRateLimiter}); this adapter only namespaces it under a
 * fixed {@code taarifu:rl:auth:*} prefix. No raw phone/email/IP ever reaches a Redis key, a log, or a metric.
 * Keys are never logged.</p>
 *
 * <p><b>Fail-closed on a counter-store outage (security over availability, AUTH-DESIGN §15):</b> if Redis is
 * unreachable, every gate method returns {@code false} (deny) and every record/reset is best-effort — the auth
 * surface tightens rather than opening when the limiter cannot see prior state. This matches the port contract
 * that the auth surface fails closed; the citizen READ path is unaffected (it does not pass through here).</p>
 *
 * <p>WHY a Redis hash for login state (not three flat keys): the failure count, window start, and next-allowed
 * instant must advance together; one hash keyed by the account hash keeps them consistent and lets a single
 * {@code DEL} reset the account, with one TTL bounding the whole window (DRY, KISS).</p>
 */
@Component("redisAuthRateLimiter")
@ConditionalOnProperty(name = "taarifu.ratelimit.backend", havingValue = "redis")
public class RedisAuthRateLimiter implements AuthRateLimiter {

    /** Root namespace for every auth rate-limit key, so the limiter's keys are isolatable/flushable. */
    static final String KEY_PREFIX = "taarifu:rl:auth:";
    /** OTP-send sub-namespace. */
    static final String OTP_SEND_NS = KEY_PREFIX + "otp-send:";
    /** OTP-verify sub-namespace. */
    static final String OTP_VERIFY_NS = KEY_PREFIX + "otp-verify:";
    /** Login-state sub-namespace. */
    static final String LOGIN_NS = KEY_PREFIX + "login:";

    // Thresholds — MUST match InMemoryAuthRateLimiter exactly (AUTH-DESIGN §9).
    static final Duration OTP_SEND_INTERVAL = Duration.ofSeconds(60);
    static final int OTP_VERIFY_MAX = 5;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    static final int LOGIN_BACKOFF_AFTER = 3;
    static final int LOGIN_LOCK_AFTER = 10;
    /** Backoff cap (seconds) — mirrors the in-memory {@code Math.min(1<<n, 300)}. */
    static final long BACKOFF_CAP_SECONDS = 300;

    /** Hash fields for the login-state hash. */
    static final String FIELD_FAILURES = "failures";
    static final String FIELD_WINDOW_START = "windowStart";
    static final String FIELD_NEXT_ALLOWED = "nextAllowedAt";

    private final StringRedisTemplate redis;
    private final ClockPort clock;

    /**
     * @param redis the shared string-valued Redis template (Lettuce); auto-configured from
     *              {@code spring.data.redis.host/port} (env, never source).
     * @param clock time source (testable) for the windows/backoff — the SAME {@link ClockPort} the rest of
     *              the codebase shares, so backoff math is deterministic in tests.
     */
    public RedisAuthRateLimiter(StringRedisTemplate redis, ClockPort clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Atomic first-in-window admission via {@code SET key 1 NX EX 60}: returns {@code true} only for the
     * call that creates the key; any resend inside the 60s TTL finds the key present and is denied. Fails
     * closed (denies) on a Redis error.</p>
     */
    @Override
    public boolean allowOtpSend(String recipientHash) {
        String key = OTP_SEND_NS + recipientHash;
        try {
            // SET key 1 NX EX 60 — only the first send in the 60s window creates the key (returns true);
            // any resend before the TTL lapses finds it present (returns false). One atomic round-trip.
            Boolean created = redis.opsForValue().setIfAbsent(key, "1", OTP_SEND_INTERVAL);
            return Boolean.TRUE.equals(created);
        } catch (RuntimeException ex) {
            return false; // fail closed (AUTH-DESIGN §15)
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code INCR} the per-challenge counter; on the first hit set the per-challenge TTL so an abandoned
     * challenge self-cleans. Admits iff the post-increment count {@code <= 5}. Fails closed on a Redis error.</p>
     */
    @Override
    public boolean allowOtpVerifyAttempt(String challengeKey) {
        String key = OTP_VERIFY_NS + challengeKey;
        try {
            Long attempts = redis.opsForValue().increment(key);
            if (attempts != null && attempts == 1L) {
                // Bound the counter's lifetime to the OTP window so a never-completed challenge does not leak.
                redis.expire(key, OTP_SEND_INTERVAL.multipliedBy(OTP_VERIFY_MAX));
            }
            return attempts != null && attempts <= OTP_VERIFY_MAX;
        } catch (RuntimeException ex) {
            return false; // fail closed
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the login-state hash and applies the SAME decision tree as the in-memory adapter: no state →
     * allow; window elapsed (TTL would already have expired, but defensive) → allow; {@code >= 10} failures →
     * hard-deny; {@code >= 3} failures and still inside the backoff → deny; else allow. Fails closed on error.</p>
     */
    @Override
    public boolean allowLoginAttempt(String accountHash) {
        String key = LOGIN_NS + accountHash;
        try {
            var ops = redis.opsForHash();
            Object failuresRaw = ops.get(key, FIELD_FAILURES);
            if (failuresRaw == null) {
                return true; // no state → allow
            }
            int failures = parseInt(failuresRaw);
            Instant now = clock.now();
            Instant windowStart = parseInstant(ops.get(key, FIELD_WINDOW_START));
            if (windowStart != null && windowStart.plus(LOGIN_WINDOW).isBefore(now)) {
                redis.delete(key); // window elapsed → reset
                return true;
            }
            if (failures >= LOGIN_LOCK_AFTER) {
                return false; // hard lock for the remainder of the window
            }
            Instant nextAllowed = parseInstant(ops.get(key, FIELD_NEXT_ALLOWED));
            if (failures >= LOGIN_BACKOFF_AFTER && nextAllowed != null && now.isBefore(nextAllowed)) {
                return false; // still backing off
            }
            return true;
        } catch (RuntimeException ex) {
            return false; // fail closed
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Advances the login-state hash: resets it when absent or its window has elapsed, increments failures,
     * and (from the 3rd failure) recomputes {@code nextAllowedAt = now + min(2^(failures-3), 300)s}, identical
     * to the in-memory backoff. Re-applies the {@link #LOGIN_WINDOW} TTL so the whole window self-expires.
     * Best-effort on a Redis error (a missed record never opens the gate — the next failure re-establishes it).</p>
     */
    @Override
    public void recordLoginFailure(String accountHash) {
        String key = LOGIN_NS + accountHash;
        try {
            var ops = redis.opsForHash();
            Instant now = clock.now();
            Instant windowStart = parseInstant(ops.get(key, FIELD_WINDOW_START));
            Object failuresRaw = ops.get(key, FIELD_FAILURES);
            boolean freshWindow = failuresRaw == null
                    || windowStart == null
                    || windowStart.plus(LOGIN_WINDOW).isBefore(now);
            int failures = freshWindow ? 0 : parseInt(failuresRaw);
            failures++;
            if (freshWindow) {
                ops.put(key, FIELD_WINDOW_START, Long.toString(now.toEpochMilli()));
            }
            ops.put(key, FIELD_FAILURES, Integer.toString(failures));
            if (failures >= LOGIN_BACKOFF_AFTER) {
                long backoffSeconds = Math.min(1L << (failures - LOGIN_BACKOFF_AFTER), BACKOFF_CAP_SECONDS);
                ops.put(key, FIELD_NEXT_ALLOWED, Long.toString(now.plusSeconds(backoffSeconds).toEpochMilli()));
            }
            // Bound the whole window so a number that fails a few times then goes quiet self-cleans.
            redis.expire(key, LOGIN_WINDOW);
        } catch (RuntimeException ex) {
            // Best-effort: a dropped failure does not unlock anything already locked; the next failure recovers.
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the account's login-state hash on a successful login. Best-effort on a Redis error: a lingering
     * lock simply expires with its {@link #LOGIN_WINDOW} TTL (fail-safe — a transient delete miss never weakens
     * the control, it only delays a legitimate reset to the window edge).</p>
     */
    @Override
    public void resetLogin(String accountHash) {
        try {
            redis.delete(LOGIN_NS + accountHash);
        } catch (RuntimeException ex) {
            // Best-effort; the TTL bounds any stale state.
        }
    }

    /** Parses a Redis hash value to int, tolerating {@code null} (treated as 0). */
    private static int parseInt(Object raw) {
        return raw == null ? 0 : Integer.parseInt(raw.toString());
    }

    /** Parses a stored epoch-milli instant, tolerating {@code null}. */
    private static Instant parseInstant(Object raw) {
        return raw == null ? null : Instant.ofEpochMilli(Long.parseLong(raw.toString()));
    }
}
