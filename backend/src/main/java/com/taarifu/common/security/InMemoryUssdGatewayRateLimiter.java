package com.taarifu.common.security;

import com.taarifu.common.domain.port.ClockPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default, infra-free {@link UssdGatewayRateLimiter} — an in-memory sliding window per hashed MSISDN
 * (wave2-review P2-1, S-2).
 *
 * <p>Responsibility: provides working anti-automation for the USSD webhook with <b>no external
 * dependency</b>, so the channel can be exposed with a real (testable) limit now. Two independent
 * fixed-window counters per hashed MSISDN:
 * <ul>
 *   <li><b>session turns</b> — {@code MAX_TURNS} keypresses per {@code TURN_WINDOW} (caps the keypress /
 *       report-filing rate from one number);</li>
 *   <li><b>new sessions</b> — {@code MAX_NEW_SESSIONS} fresh dialogues per {@code NEW_SESSION_WINDOW}
 *       (caps the unauthenticated, no-OTP account-creation trigger more tightly).</li>
 * </ul>
 * Both windows are sliding (timestamps pruned on each check) and keyed by a <b>hashed</b> MSISDN supplied
 * by the caller — this class never sees a raw phone (S-4, PDPA).</p>
 *
 * <p>WHY this is acceptable as the default (and what production changes): in-memory counters are lost on
 * restart and are not shared across instances, so they are not a hardened multi-instance control on their
 * own — a Redis-backed adapter ({@code RedisUssdGatewayRateLimiter}) is required for production (TR-3) and
 * swaps in behind {@link UssdGatewayRateLimiter}. The two are mutually exclusive on
 * {@code taarifu.ratelimit.backend}: this adapter is the {@code matchIfMissing}/{@code =memory} default,
 * the Redis adapter is selected only by {@code =redis}, so exactly one bean resolves in every environment
 * and a context with no Redis still boots (W3-2). This adapter makes the seam real and unit-testable now.
 * Bounded memory: idle MSISDN entries are dropped once their windows fully drain.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.ratelimit.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryUssdGatewayRateLimiter implements UssdGatewayRateLimiter {

    /** Per-MSISDN keypress cap: at most {@value} turns within {@link #TURN_WINDOW}. */
    private static final int MAX_TURNS = 30;
    private static final Duration TURN_WINDOW = Duration.ofMinutes(1);

    /** Per-MSISDN new-dialogue (account-creation) cap: at most {@value} within {@link #NEW_SESSION_WINDOW}. */
    private static final int MAX_NEW_SESSIONS = 5;
    private static final Duration NEW_SESSION_WINDOW = Duration.ofMinutes(10);

    private final ClockPort clock;

    /** Keypress timestamps per MSISDN hash (sliding {@link #TURN_WINDOW}). */
    private final Map<String, Deque<Instant>> turnHits = new ConcurrentHashMap<>();
    /** New-dialogue timestamps per MSISDN hash (sliding {@link #NEW_SESSION_WINDOW}). */
    private final Map<String, Deque<Instant>> newSessionHits = new ConcurrentHashMap<>();

    /**
     * @param clock time source (testable) for the sliding windows.
     */
    public InMemoryUssdGatewayRateLimiter(ClockPort clock) {
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowSessionTurn(String msisdnHash) {
        return admit(turnHits, msisdnHash, TURN_WINDOW, MAX_TURNS);
    }

    /** {@inheritDoc} */
    @Override
    public boolean allowNewSession(String msisdnHash) {
        return admit(newSessionHits, msisdnHash, NEW_SESSION_WINDOW, MAX_NEW_SESSIONS);
    }

    /**
     * Sliding-window admission: prunes hits older than {@code window}, then admits and records {@code now}
     * iff fewer than {@code max} hits remain in the window. Drops the entry when the window fully drains so
     * idle numbers do not accumulate memory.
     *
     * @param store  the per-hash timestamp deques for this dimension.
     * @param key    the hashed MSISDN.
     * @param window the sliding window length.
     * @param max    the maximum hits permitted within the window.
     * @return {@code true} if admitted (and recorded); {@code false} if the cap is already reached.
     */
    private boolean admit(Map<String, Deque<Instant>> store, String key, Duration window, int max) {
        Instant now = clock.now();
        Instant cutoff = now.minus(window);
        // compute() holds the per-key bin's monitor for the whole prune+admit, so concurrent turns for the
        // same MSISDN cannot both slip past the cap (ConcurrentHashMap gives per-bin atomicity).
        boolean[] admitted = {false};
        store.compute(key, (k, hits) -> {
            Deque<Instant> q = (hits == null) ? new ArrayDeque<>() : hits;
            while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
                q.pollFirst();
            }
            if (q.size() < max) {
                q.addLast(now);
                admitted[0] = true;
            }
            return q.isEmpty() ? null : q;
        });
        return admitted[0];
    }
}
