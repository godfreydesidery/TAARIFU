package com.taarifu.common.security;

import com.taarifu.common.domain.port.ClockPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InMemoryUssdGatewayRateLimiter} — the open-webhook anti-automation primitive
 * (wave2-review P2-1, THREAT-MODEL TB-3, S-2).
 *
 * <p>Responsibility: proves the per-MSISDN keypress (session-turn) cap and the tighter new-dialogue
 * (account-creation) cap, that the two windows are independent, that windows slide (a later call is
 * admitted once the window has elapsed), and that limits are isolated per hashed MSISDN. Uses a
 * controllable {@link ClockPort} (no sleeping, no flakiness, no Docker).</p>
 */
class InMemoryUssdGatewayRateLimiterTest {

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

    private static final String MSISDN_HASH = "hash-A";

    @Test
    void sessionTurns_areCappedPerMsisdnWithinTheWindow() {
        InMemoryUssdGatewayRateLimiter limiter = new InMemoryUssdGatewayRateLimiter(new MutableClock());
        // 30 turns allowed in the 1-minute window; the 31st trips.
        for (int i = 0; i < 30; i++) {
            assertThat(limiter.allowSessionTurn(MSISDN_HASH)).as("turn %s", i).isTrue();
        }
        assertThat(limiter.allowSessionTurn(MSISDN_HASH)).isFalse();
    }

    @Test
    void sessionTurnWindow_slidesSoTurnsAreAdmittedAgainLater() {
        MutableClock clock = new MutableClock();
        InMemoryUssdGatewayRateLimiter limiter = new InMemoryUssdGatewayRateLimiter(clock);
        for (int i = 0; i < 30; i++) {
            limiter.allowSessionTurn(MSISDN_HASH);
        }
        assertThat(limiter.allowSessionTurn(MSISDN_HASH)).isFalse(); // capped

        clock.advanceSeconds(61); // whole 1-minute window has elapsed
        assertThat(limiter.allowSessionTurn(MSISDN_HASH)).isTrue();   // admitted again
    }

    @Test
    void newSessions_areCappedMoreTightlyThanTurns() {
        InMemoryUssdGatewayRateLimiter limiter = new InMemoryUssdGatewayRateLimiter(new MutableClock());
        // 5 new dialogues allowed in the 10-minute window; the 6th trips.
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allowNewSession(MSISDN_HASH)).as("new-session %s", i).isTrue();
        }
        assertThat(limiter.allowNewSession(MSISDN_HASH)).isFalse();
    }

    @Test
    void newSessionAndTurnWindows_areIndependent() {
        InMemoryUssdGatewayRateLimiter limiter = new InMemoryUssdGatewayRateLimiter(new MutableClock());
        // Exhaust the new-session cap; the keypress (turn) cap is untouched and still admits.
        for (int i = 0; i < 5; i++) {
            limiter.allowNewSession(MSISDN_HASH);
        }
        assertThat(limiter.allowNewSession(MSISDN_HASH)).isFalse();
        assertThat(limiter.allowSessionTurn(MSISDN_HASH)).isTrue();
    }

    @Test
    void limitsAreIsolatedPerMsisdn() {
        InMemoryUssdGatewayRateLimiter limiter = new InMemoryUssdGatewayRateLimiter(new MutableClock());
        for (int i = 0; i < 5; i++) {
            limiter.allowNewSession("hash-A");
        }
        assertThat(limiter.allowNewSession("hash-A")).isFalse(); // A is capped
        assertThat(limiter.allowNewSession("hash-B")).isTrue();  // B is unaffected
    }
}
