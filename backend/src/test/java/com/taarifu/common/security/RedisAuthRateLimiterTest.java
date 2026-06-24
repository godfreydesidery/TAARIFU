package com.taarifu.common.security;

import com.taarifu.common.domain.port.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisAuthRateLimiter} — proves the KEY construction and TTL/decision logic of the
 * multi-instance auth rate-limiter against a <b>mocked</b> {@link StringRedisTemplate} (no embedded or real
 * Redis server, per the W3-2 brief). These assert the load-bearing wire behaviour: that the adapter uses the
 * right namespaced keys, the right TTLs, the {@code SETNX}/{@code INCR} primitives, the same thresholds as the
 * in-memory adapter, and that it fails closed when Redis throws.
 *
 * <p>WHY mock the template (not embed Redis): the contract under test is "which Redis command, on which key,
 * with which TTL" plus the pure decision tree — all observable on a mock. Embedding a server would test
 * Lettuce/Redis, not this adapter's logic, and would add a heavy test dependency for no extra coverage.</p>
 */
class RedisAuthRateLimiterTest {

    /** A clock whose "now" the test advances explicitly (mirrors the in-memory adapter's test clock). */
    private static final class MutableClock implements ClockPort {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-24T10:00:00Z"));

        @Override
        public Instant now() {
            return now.get();
        }

        void advanceSeconds(long seconds) {
            now.updateAndGet(t -> t.plusSeconds(seconds));
        }
    }

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private MutableClock clock;
    private RedisAuthRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        clock = new MutableClock();
        limiter = new RedisAuthRateLimiter(redis, clock);
    }

    // --------------------------------------------------------------------- OTP send

    @Test
    void otpSend_usesSetNxWithSixtySecondTtlOnTheNamespacedKey() {
        when(valueOps.setIfAbsent(eq("taarifu:rl:auth:otp-send:rh"), eq("1"),
                eq(Duration.ofSeconds(60)))).thenReturn(true);

        assertThat(limiter.allowOtpSend("rh")).isTrue();

        // First send wins (SETNX created the key); the exact key + value + 60s TTL are asserted via the stub.
        verify(valueOps).setIfAbsent("taarifu:rl:auth:otp-send:rh", "1", Duration.ofSeconds(60));
    }

    @Test
    void otpSend_isDeniedWhenKeyAlreadyPresentWithinWindow() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false); // key already set

        assertThat(limiter.allowOtpSend("rh")).isFalse();
    }

    @Test
    void otpSend_failsClosedWhenRedisThrows() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(limiter.allowOtpSend("rh")).isFalse(); // security over availability (AUTH-DESIGN §15)
    }

    // --------------------------------------------------------------------- OTP verify

    @Test
    void otpVerify_incrementsAndSetsTtlOnFirstAttemptThenCapsAtFive() {
        String key = "taarifu:rl:auth:otp-verify:ch";
        when(valueOps.increment(key)).thenReturn(1L, 2L, 3L, 4L, 5L, 6L);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allowOtpVerifyAttempt("ch")).as("attempt %s", i).isTrue();
        }
        assertThat(limiter.allowOtpVerifyAttempt("ch")).isFalse(); // 6th blocked (cap = 5)

        // TTL is set exactly once — on the first attempt (count == 1) — bounding an abandoned challenge.
        verify(redis).expire(eq(key), eq(Duration.ofSeconds(60).multipliedBy(5)));
    }

    @Test
    void otpVerify_failsClosedWhenRedisThrows() {
        when(valueOps.increment(any())).thenThrow(new RuntimeException("redis down"));

        assertThat(limiter.allowOtpVerifyAttempt("ch")).isFalse();
    }

    // --------------------------------------------------------------------- Login lockout / backoff

    /**
     * Wires a mock {@link HashOperations} backed by an in-test map so the login-state read-modify-write is
     * exercised against real stored values (epoch-millis strings), proving the same decision tree and backoff
     * math as the in-memory adapter.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> wireLoginHash(String accountKey) {
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        Map<String, String> store = new HashMap<>();
        when(redis.opsForHash()).thenReturn((HashOperations) hashOps);
        when(hashOps.get(eq(accountKey), any())).thenAnswer(inv -> store.get(inv.getArgument(1).toString()));
        lenient().doAnswer(inv -> {
            store.put(inv.getArgument(1).toString(), inv.getArgument(2).toString());
            return null;
        }).when(hashOps).put(eq(accountKey), any(), any());
        lenient().when(redis.delete(accountKey)).thenAnswer(inv -> {
            boolean had = !store.isEmpty();
            store.clear();
            return had;
        });
        return store;
    }

    @Test
    void login_allowedWhenNoState() {
        wireLoginHash("taarifu:rl:auth:login:acc");
        assertThat(limiter.allowLoginAttempt("acc")).isTrue();
    }

    @Test
    void login_hardLocksAfterTenFailuresAndSetsWindowTtl() {
        String key = "taarifu:rl:auth:login:acc";
        wireLoginHash(key);

        for (int i = 0; i < 10; i++) {
            limiter.recordLoginFailure("acc");
        }
        assertThat(limiter.allowLoginAttempt("acc")).isFalse(); // hard lock at 10 (matches in-memory)

        // Each failure re-applies the 15-minute window TTL so the whole state self-expires.
        verify(redis, org.mockito.Mockito.atLeastOnce())
                .expire(eq(key), eq(Duration.ofMinutes(15)));
    }

    @Test
    void login_backsOffAfterThreeFailuresThenAdmitsOnceBackoffElapses() {
        String key = "taarifu:rl:auth:login:acc";
        wireLoginHash(key);

        for (int i = 0; i < 3; i++) {
            limiter.recordLoginFailure("acc");
        }
        // After the 3rd failure backoff = min(2^(3-3),300) = 1s; still inside it → denied.
        assertThat(limiter.allowLoginAttempt("acc")).isFalse();

        clock.advanceSeconds(2); // past the 1s backoff, still inside the 15-min window
        assertThat(limiter.allowLoginAttempt("acc")).isTrue();
    }

    @Test
    void login_resetDeletesTheStateKey() {
        String key = "taarifu:rl:auth:login:acc";
        Map<String, String> store = wireLoginHash(key);
        store.put(RedisAuthRateLimiter.FIELD_FAILURES, "5");

        limiter.resetLogin("acc");

        verify(redis).delete(key);
        assertThat(store).isEmpty();
    }

    @Test
    void login_windowElapsedResetsAndAllows() {
        String key = "taarifu:rl:auth:login:acc";
        Map<String, String> store = wireLoginHash(key);
        // Seed a stale window: started 16 minutes ago (beyond the 15-min window), 10 failures.
        Instant stale = clock.now().minus(Duration.ofMinutes(16));
        store.put(RedisAuthRateLimiter.FIELD_FAILURES, "10");
        store.put(RedisAuthRateLimiter.FIELD_WINDOW_START, Long.toString(stale.toEpochMilli()));

        assertThat(limiter.allowLoginAttempt("acc")).isTrue(); // window elapsed → reset → allow
        verify(redis).delete(key);
    }

    @Test
    void login_allowFailsClosedWhenRedisThrows() {
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn((HashOperations) hashOps);
        when(hashOps.get(any(), any())).thenThrow(new RuntimeException("redis down"));

        assertThat(limiter.allowLoginAttempt("acc")).isFalse();
    }

    @Test
    void recordFailure_isBestEffortAndSwallowsRedisErrors() {
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn((HashOperations) hashOps);
        when(hashOps.get(any(), any())).thenThrow(new RuntimeException("redis down"));

        // Must not propagate — a dropped failure record never crashes the login path.
        limiter.recordLoginFailure("acc");
        verify(hashOps, never()).put(any(), any(), any());
    }

    @Test
    void otpSend_keyIncludesTheCallerSuppliedHashVerbatim() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(valueOps.setIfAbsent(keyCaptor.capture(), any(), any(Duration.class))).thenReturn(true);

        limiter.allowOtpSend("HASHED-RECIPIENT");

        // No PII transformation here — the caller already hashed; the adapter only namespaces (S-4).
        assertThat(keyCaptor.getValue()).isEqualTo("taarifu:rl:auth:otp-send:HASHED-RECIPIENT");
    }
}
