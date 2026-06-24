package com.taarifu.ussd.infrastructure.adapter;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.security.UssdGatewayRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The production, multi-instance {@link UssdGatewayRateLimiter} — a Redis-backed sliding window per hashed
 * MSISDN, sharing state across every app instance (W3-2, wave2-review P2-1, THREAT-MODEL TB-3/TR-1/TR-3, S-2).
 *
 * <p>Responsibility: the same per-MSISDN anti-automation contract as
 * {@code InMemoryUssdGatewayRateLimiter}, but with the counters in <b>Redis</b> so a horizontally-scaled
 * deployment caps keypress + no-OTP account-creation abuse against ONE shared budget per number. Behind a
 * load balancer the in-memory adapter's per-process budget lets an aggregator (or a flood relayed through one)
 * multiply the effective cap by the instance count; this adapter closes that gap (TR-3). Selected by
 * {@code taarifu.ratelimit.backend=redis}; when this bean is present (registered under the name
 * {@code redisUssdGatewayRateLimiter}) {@code InMemoryUssdGatewayRateLimiter} yields to it via its
 * {@code @ConditionalOnMissingBean(name = "redisUssdGatewayRateLimiter")}. With the property unset/=memory
 * this bean is absent and the in-memory adapter is the single-instance default, so dev, test, and a
 * single-node deployment are unchanged and still boot with no Redis present.</p>
 *
 * <p><b>Thresholds are identical to the in-memory adapter</b> (the contract the webhook depends on): at most
 * 30 session turns (keypresses) per 1-minute window, and the tighter 5 new dialogues (account-creation
 * trigger) per 10-minute window — both per hashed MSISDN, both <b>sliding</b>. The sliding semantics are
 * preserved with a Redis <b>sorted set</b> per dimension, scored by event time (epoch-millis):
 * {@code ZREMRANGEBYSCORE} prunes hits older than {@code now - window}, {@code ZCARD} reads the live count,
 * and a hit is admitted (and recorded with {@code ZADD}) iff the live count is below the cap — exactly the
 * in-memory deque prune-then-admit, race-safe across instances because each dimension's check runs against
 * the single shared set. A per-key {@code EXPIRE} (= the window) lets an idle number's set self-clean, the
 * same "drop the entry once the window drains" memory bound the in-memory adapter has.</p>
 *
 * <p><b>Keys carry no PII (S-4, PDPA 2022/2023):</b> the caller already passes a <b>hashed</b>
 * (blind-indexed) MSISDN (see {@link UssdGatewayRateLimiter}); this adapter only namespaces it under a fixed
 * {@code taarifu:rl:ussd:*} prefix. No raw phone ever reaches a Redis key, a log, or a metric. Keys are never
 * logged.</p>
 *
 * <p><b>Fail-closed on a counter-store outage:</b> if Redis is unreachable a check returns {@code false}
 * (deny the turn / deny the new dialogue) — the open, unauthenticated webhook tightens rather than opening
 * when the limiter cannot see prior state. The aggregator simply receives the throttle response; a genuine
 * citizen retries (the dialogue is short-lived), and no flood is admitted unmetered.</p>
 *
 * <p><b>Boundary note (ADR-0013):</b> this adapter lives in the USSD module's {@code infrastructure.adapter}
 * (where the module README anticipated the Redis swap) and implements the shared-kernel
 * {@link UssdGatewayRateLimiter} port — a sanctioned cross-module {@code domain.port} implementation. It
 * depends only on {@code common} ({@link ClockPort}, the port) and the Redis client; it reaches into no other
 * module's internals.</p>
 *
 * <p>WHY a sorted set (not the auth limiter's {@code INCR}/{@code SETNX}): the USSD caps are true sliding
 * windows over the last N seconds (a steady drip must keep being admitted as old hits age out), which a flat
 * fixed-window counter cannot model without edge bursts. The sorted set is the natural Redis structure for a
 * sliding window and mirrors the in-memory deque one-to-one (KISS, DRY of semantics).</p>
 */
@Component("redisUssdGatewayRateLimiter")
@ConditionalOnProperty(name = "taarifu.ratelimit.backend", havingValue = "redis")
public class RedisUssdGatewayRateLimiter implements UssdGatewayRateLimiter {

    /** Root namespace for every USSD rate-limit key, so the limiter's keys are isolatable/flushable. */
    static final String KEY_PREFIX = "taarifu:rl:ussd:";
    /** Session-turn (keypress) sub-namespace. */
    static final String TURN_NS = KEY_PREFIX + "turn:";
    /** New-dialogue (account-creation) sub-namespace. */
    static final String NEW_SESSION_NS = KEY_PREFIX + "new:";

    // Thresholds — MUST match InMemoryUssdGatewayRateLimiter exactly (wave2-review P2-1).
    static final int MAX_TURNS = 30;
    static final Duration TURN_WINDOW = Duration.ofMinutes(1);
    static final int MAX_NEW_SESSIONS = 5;
    static final Duration NEW_SESSION_WINDOW = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ClockPort clock;

    /**
     * @param redis the shared string-valued Redis template (Lettuce); auto-configured from
     *              {@code spring.data.redis.host/port} (env, never source).
     * @param clock time source (testable) for the sliding windows — the SAME {@link ClockPort} the rest of
     *              the codebase shares, so window math is deterministic in tests.
     */
    public RedisUssdGatewayRateLimiter(StringRedisTemplate redis, ClockPort clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowSessionTurn(String msisdnHash) {
        return admit(TURN_NS + msisdnHash, TURN_WINDOW, MAX_TURNS);
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowNewSession(String msisdnHash) {
        return admit(NEW_SESSION_NS + msisdnHash, NEW_SESSION_WINDOW, MAX_NEW_SESSIONS);
    }

    /**
     * Sliding-window admission against a Redis sorted set scored by event time: prunes hits older than
     * {@code now - window} ({@code ZREMRANGEBYSCORE}), reads the live count ({@code ZCARD}), and admits — and
     * records {@code now} ({@code ZADD}) plus refreshes the {@code EXPIRE} — iff the live count is below
     * {@code max}. Mirrors the in-memory deque prune-then-admit exactly. Fails closed (denies) on a Redis error.
     *
     * @param key    the namespaced per-MSISDN sorted-set key.
     * @param window the sliding window length.
     * @param max    the maximum hits permitted within the window.
     * @return {@code true} if admitted (and recorded); {@code false} if the cap is already reached or Redis
     *         is unreachable.
     */
    private boolean admit(String key, Duration window, int max) {
        Instant now = clock.now();
        long nowMs = now.toEpochMilli();
        double cutoff = (double) (nowMs - window.toMillis());
        try {
            var zset = redis.opsForZSet();
            // Drop hits that have aged out of the window (inclusive of the exact cutoff, matching the
            // in-memory "!peekFirst().isAfter(cutoff)" prune).
            zset.removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff);
            Long live = zset.zCard(key);
            long count = live == null ? 0L : live;
            if (count >= max) {
                return false;
            }
            // Record this hit. The member is the timestamp; a same-millisecond second hit for one MSISDN would
            // collide as a ZSET member, so disambiguate with a short random suffix to never under-count.
            String member = nowMs + ":" + Long.toHexString(System.nanoTime());
            zset.add(key, member, (double) nowMs);
            // Bound the set's lifetime to the window so an idle number's key self-cleans (memory bound).
            redis.expire(key, window);
            return true;
        } catch (RuntimeException ex) {
            return false; // fail closed
        }
    }
}
