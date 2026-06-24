package com.taarifu.ussd;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.ussd.infrastructure.adapter.RedisUssdGatewayRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisUssdGatewayRateLimiter} — proves the sliding-window KEY construction, TTL, and
 * cap logic of the multi-instance USSD limiter against a <b>mocked</b> {@link StringRedisTemplate} backed by a
 * tiny in-test sorted set (no embedded or real Redis server, per the W3-2 brief).
 *
 * <p>Responsibility: assert the same observable behaviour the in-memory adapter's tests pin — per-MSISDN
 * keypress cap (30/1min) and the tighter new-dialogue cap (5/10min), windows that slide, isolation per hashed
 * MSISDN, the correct namespaced keys + window TTL, and fail-closed on a Redis error — by simulating the
 * sorted-set prune/count/add the adapter drives.</p>
 */
class RedisUssdGatewayRateLimiterTest {

    /** A clock whose "now" the test advances explicitly. */
    private static final class MutableClock implements ClockPort {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-24T08:00:00Z"));

        @Override
        public Instant now() {
            return now.get();
        }

        void advanceSeconds(long seconds) {
            now.updateAndGet(t -> t.plusSeconds(seconds));
        }
    }

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private MutableClock clock;
    private RedisUssdGatewayRateLimiter limiter;

    /** In-test sorted sets keyed by Redis key → list of member scores (epoch-millis), simulating the ZSET. */
    private final Map<String, List<Double>> sets = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        clock = new MutableClock();
        limiter = new RedisUssdGatewayRateLimiter(redis, clock);

        // ZREMRANGEBYSCORE: drop scores <= max (inclusive cutoff, matching the adapter's prune).
        lenient().when(zset.removeRangeByScore(anyString(), eq(Double.NEGATIVE_INFINITY), anyDouble()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    double max = inv.getArgument(2);
                    List<Double> members = sets.computeIfAbsent(key, k -> new ArrayList<>());
                    long before = members.size();
                    members.removeIf(score -> score <= max);
                    return before - members.size();
                });
        // ZCARD: live count.
        lenient().when(zset.zCard(anyString()))
                .thenAnswer(inv -> (long) sets.computeIfAbsent(inv.getArgument(0), k -> new ArrayList<>()).size());
        // ZADD: record a hit at the given score.
        lenient().when(zset.add(anyString(), anyString(), anyDouble()))
                .thenAnswer(inv -> {
                    sets.computeIfAbsent(inv.getArgument(0), k -> new ArrayList<>()).add(inv.getArgument(2));
                    return true;
                });
        lenient().when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
    }

    // --------------------------------------------------------------------- session turns

    @Test
    void sessionTurns_areCappedAtThirtyPerMinuteAndUseTheNamespacedKeyAndWindowTtl() {
        for (int i = 0; i < 30; i++) {
            assertThat(limiter.allowSessionTurn("hA")).as("turn %s", i).isTrue();
        }
        assertThat(limiter.allowSessionTurn("hA")).isFalse(); // 31st trips (cap = 30)

        // Correct namespaced key and the 1-minute window TTL on every admitted hit.
        verify(redis, org.mockito.Mockito.atLeastOnce())
                .expire(eq("taarifu:rl:ussd:turn:hA"), eq(Duration.ofMinutes(1)));
    }

    @Test
    void sessionTurnWindow_slidesSoTurnsAreAdmittedAgainAfterTheWindowElapses() {
        for (int i = 0; i < 30; i++) {
            limiter.allowSessionTurn("hA");
        }
        assertThat(limiter.allowSessionTurn("hA")).isFalse(); // capped

        clock.advanceSeconds(61); // whole 1-minute window has elapsed → old hits prune out
        assertThat(limiter.allowSessionTurn("hA")).isTrue();   // admitted again
    }

    // --------------------------------------------------------------------- new sessions

    @Test
    void newSessions_areCappedAtFivePerTenMinutesWithTheTighterKeyAndTtl() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allowNewSession("hA")).as("new-session %s", i).isTrue();
        }
        assertThat(limiter.allowNewSession("hA")).isFalse(); // 6th trips (cap = 5)

        verify(redis, org.mockito.Mockito.atLeastOnce())
                .expire(eq("taarifu:rl:ussd:new:hA"), eq(Duration.ofMinutes(10)));
    }

    @Test
    void turnAndNewSessionWindows_areIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.allowNewSession("hA");
        }
        assertThat(limiter.allowNewSession("hA")).isFalse(); // new-session cap reached
        assertThat(limiter.allowSessionTurn("hA")).isTrue();  // the keypress window is untouched
    }

    @Test
    void limitsAreIsolatedPerHashedMsisdn() {
        for (int i = 0; i < 5; i++) {
            limiter.allowNewSession("hA");
        }
        assertThat(limiter.allowNewSession("hA")).isFalse(); // A capped
        assertThat(limiter.allowNewSession("hB")).isTrue();  // B unaffected (separate key)
    }

    // --------------------------------------------------------------------- key / fail-closed

    @Test
    void keyEmbedsTheCallerSuppliedHashVerbatimWithNoPiiTransformation() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

        limiter.allowSessionTurn("HASHED-MSISDN");

        verify(zset).add(keyCaptor.capture(), any(), anyDouble());
        // The caller already blind-indexed the MSISDN; the adapter only namespaces it (S-4, PDPA).
        assertThat(keyCaptor.getValue()).isEqualTo("taarifu:rl:ussd:turn:HASHED-MSISDN");
    }

    @Test
    void failsClosedWhenRedisThrows() {
        when(zset.zCard(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(limiter.allowSessionTurn("hA")).isFalse();  // deny the turn on outage
        assertThat(limiter.allowNewSession("hA")).isFalse();   // deny the new dialogue on outage
    }
}
